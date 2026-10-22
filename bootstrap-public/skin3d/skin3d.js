/*
 * QookiX 皮肤 3D 预览宿主（WebView 侧）。
 *
 * ── 为什么放在 WebView 里而不是纯 Compose ─────────────────────────────────
 * 旧 UI 用 `skinview3d`（Three.js 封装）做 3D 预览 —— 可拖动旋转、
 * 待机/走路/跑步动画、经典/纤细模型。Compose **没有 3D 引擎**，
 * 而我们要的效果与旧 UI 完全一致。
 *
 * 三个选项：
 *   1. GLSurfaceView + 自己写 OpenGL ES（骨骼/贴图/光照）—— 工作量大
 *   2. 原生 WebGL（Compose 里没有等价物）
 *   3. **复用已有的隐形 WebView 跑 skinview3d**（本实现）
 *
 * 选 3：那个 WebView **本来就在**（承载 Tauri IPC 注入），
 * 复用它不新增进程、不破坏「界面 100% Compose」的设计 ——
 * 它依然不可见，只是多担了一份「离屏渲染」的职责。
 *
 * ── ⚠️ 关键：为什么用 gl.readPixels 而不是 canvas.toDataURL ───────────────
 * 真机实测（OnePlus 8 / Android 16）探针结果：
 *
 *   readback:empty:166        ← WebGL canvas 的 toDataURL 返回空白
 *   rb2d:empty:166            ← 普通 2D canvas 的 toDataURL **也**空白
 *   rbAttached:empty:162      ← 挂进 DOM 再读，还是空白
 *   rbPixels:ok:nonzero=128   ← ✅ gl.readPixels 能拿到真实像素
 *   rbTriangle:ok:reds=4096/4096  ← ✅ 着色器渲染的结果一个像素不差
 *
 * 结论：这个不可见 WebView 里 `toDataURL` 整体不可用（Android WebView
 * 对不可见/离屏画布的已知限制），但 **`gl.readPixels` 完全正常**。
 * 所以回传路径走 readPixels → RGBA → base64 PNG（用 2D canvas 手工编码）。
 *
 * ── 接口（Kotlin 侧通过 evaluateJavascript 调用）──────────────────────────
 *   QkSkin3D.ready()                  → 是否已初始化
 *   QkSkin3D.load(dataUrl, model)     → 加载皮肤（data URL）
 *   QkSkin3D.setAnimation(name)       → idle / walk / run / none
 *   QkSkin3D.setModel('default'|'slim')
 *   QkSkin3D.setView(yawDeg, pitchDeg, zoom)
 *   QkSkin3D.snapshot(maxW, maxH)     → 返回 data URL（readPixels 编码）
 *   QkSkin3D.reset()
 */
(function () {
  "use strict";

  // 输出尺寸（与 Kotlin 侧 Skin3dHost.OUT_W/OUT_H 对应）。
  //
  // ⚠️ **与 canvas 尺寸保持一致**（192×240）—— 这样 `snapshot` 里
  // 不需要走 `drawImage` 缩放那一步（`createImageData` → `putImageData`
  // → `drawImage` → `getImageData` 四次全量拷贝），省下可观的 CPU。
  //
  // 为什么是 192×240：预览框实际显示约 165×192dp，
  // 192×240 已经比显示尺寸大，肉眼看不出差别。
  var SNAP_W = 192;
  var SNAP_H = 240;

  var SK = window.skinview3d;
  if (!SK || !SK.SkinViewer) {
    console.log("[qk-skin3d] skinview3d 未加载");
    return;
  }

  var viewer = null;
  var canvas = null;
  var currentDataUrl = null;
  var currentModel = "default";
  var currentAnim = "idle";
  // 加载序号：每次 load() 自增。
  //
  // ⚠️ 为什么需要它 —— 真机 bug（2026-10-22 用户报）：
  // 「皮肤手臂是纤细，改成经典就会和第一张图一样，改成纤细就变回来了」
  // 「有时整个模型变成一块绿色色块」。
  //
  // 原因是 `viewer.loadSkin(dataUrl)` 是**异步**的（内部 loadImage → Promise），
  // 而 Kotlin 侧切模型时会连续触发两次 load（先 load(null) 清空，再 load(url)），
  // 两次的 Promise 谁先落谁说了算 —— 旧的那次（模型是旧的）可能**后**落地，
  // 把几何体又改回旧模型的手臂宽度，同时贴图已被 resetSkin 清成 null，
  // 于是渲染出「没有贴图的材质」（MeshStandardMaterial 无 map 时的默认色）。
  //
  // 用序号做「后发起者胜」：只有 seq === 当前序号的 Promise 才允许
  // 写回状态与取帧。
  var loadSeq = 0;
  // 视图状态。
  // autoRotate：模型自转开关（由 skinview3d 的 viewer.autoRotate 驱动）。
  var view = { yaw: 0, pitch: 0, zoom: 0.9, autoRotate: false };

  /** 建一个离屏 canvas 并初始化 SkinViewer。 */
  function ensure() {
    if (viewer) return true;
    try {
      canvas = document.createElement("canvas");
      // 渲染尺寸：够清晰又不过大（回传是 base64，太大会卡）
      // 渲染尺寸：够清晰又不过大。
      //
      // ⚠️ 别随意调大 —— 画面最终要 base64 穿过 binder 回传 Kotlin，
      // 尺寸翻倍则体积四倍，过大会触发 Android 的 binder 流量保护
      //（实测 480×600 / 512×640 时应用会被系统杀，表现为
      //  "一进皮肤页就退回桌面"）。详见 Skin3dHost.kt 的 OUT_W/OUT_H 注释。
      canvas.width = 192;
      canvas.height = 240;
      canvas.style.position = "fixed";
      canvas.style.left = "-10000px";
      canvas.style.top = "0";
      document.body.appendChild(canvas);

      viewer = new SK.SkinViewer({
        canvas: canvas,
        width: 192,
        height: 240,
        // ⚠️⚠️ **必须显式指定 pixelRatio = 1**（2026-10-16 修，性能）。
        //
        // 不指定的话 skinview3d 用 `window.devicePixelRatio` ——
        // 这台设备是 2.8125，于是 canvas 被放大成 **900×1125**！
        //
        // 后果（真机实测）：
        //   readPixels 要读 101 万像素 → **44–52ms/帧**
        //   而最终输出只有 256×320（8 万像素）—— 白读了 12 倍
        //
        // 指定为 1 后 canvas 就是 320×400，读取量降到 1/7.9，
        // 而输出尺寸不变（反正后面要缩放）。
        pixelRatio: 1,
        zoom: view.zoom,
        fov: 50,
        // ⚠️ 必须开 preserveDrawingBuffer，否则 readPixels 只能拿到当前帧
        // 之后被清空的缓冲（Three.js 默认每帧 clear）。
        preserveDrawingBuffer: true,
      });
      applyAnimation();
      applyView();
      return true;
    } catch (e) {
      console.log("[qk-skin3d] 初始化失败: " + (e && e.message ? e.message : e));
      return false;
    }
  }

  function applyAnimation() {
    if (!viewer) return;
    var a = null;
    if (currentAnim === "idle" && SK.IdleAnimation) a = new SK.IdleAnimation();
    else if (currentAnim === "walk" && SK.WalkingAnimation) a = new SK.WalkingAnimation();
    else if (currentAnim === "run" && SK.RunningAnimation) a = new SK.RunningAnimation();
    viewer.animation = a;
  }

  /**
   * 应用视角。
   *
   * ⚠️⚠️ 2026-10-16 修：**不要自己 set 相机位置**。
   *
   * 原来的实现直接改 `cam.position` + `cam.lookAt(0,0,0)`，
   * 结果画面是**全黑**（自检实测 `opaque=0/1012500` —— 一个非背景像素
   * 都没有）。原因是它和 skinview3d 内部的相机管理打架：
   *
   *   1. skinview3d 自己维护相机（`zoom` / `resetCameraPose()` /
   *      拖拽旋转都走它自己的那套逻辑），外部硬改会被它下一帧覆盖或
   *      产生不一致的状态；
   *   2. 更要命的是 `lookAt(0,0,0)` —— 角色模型**不是以原点为中心**的
   *      （皮肤人形的高度约 2 个方块、脚在 y≈-1），看向原点会把模型
   *      推出画面。
   *
   * 正解：只设 skinview3d 自己的属性（`zoom` / `autoRotate`），
   * 视角重置交给它自带的 `resetCameraPose()`。
   */
  function applyView() {
    if (!viewer) return;
    try {
      viewer.zoom = view.zoom;
      // 不做任何 camera.position / lookAt 操作 —— 见上面的注释。
      //
      // ⚠️ 也**不设** `viewer.autoRotate` —— 它依赖 rAF 循环，而在
      // 不可见的 WebView 里 rAF 不推进（实测 rotY 恒为 0）。
      // 自动旋转改由 setAutoRotate + stepAutoRotate 自己驱动。
      viewer.autoRotate = false;
    } catch (e) {
      /* 视图调整失败不影响渲染 */
    }
  }

  /**
   * 按给定的水平/垂直位移旋转视角（拖拽用）。
   *
   * ── 为什么用 OrbitControls 而不是直接改 camera.position ──────────────
   * 之前我手写过 `camera.position.set(...)` + `lookAt(0,0,0)`，结果画面**全黑**
   *（和 skinview3d 内部的相机管理打架，而且模型不以原点为中心）。
   *
   * `viewer.controls` 是 skinview3d 自带的 OrbitControls（默认启用），
   * 内部维护 theta/phi 球坐标 —— **这是唯一正确的旋转入口**。
   *
   * @param dx 水平位移（像素，正数 = 向右拖）
   * @param dy 垂直位移（像素，正数 = 向下拖）
   */
  function orbitBy(dx, dy) {
    if (!viewer || !viewer.controls) return false;
    try {
      var c = viewer.controls;
      // rotateLeft / rotateUp 接受**弧度**。
      // 系数 0.01：拖 100px ≈ 57°，手感接近旧 UI。
      var k = 0.01;
      if (typeof c.rotateLeft === "function") {
        c.rotateLeft(-dx * k);
        c.rotateUp(-dy * k);
      }
      // 让改动立刻反映到相机矩阵上（离屏渲染不会自动触发 update）
      if (typeof c.update === "function") c.update();
      return true;
    } catch (e) {
      return false;
    }
  }

  /**
   * 等一帧，让 three.js 把纹理上传完成。
   *
   * ⚠️ `viewer.loadSkin()` 是**异步**的（旧 UI 里一直是
   * `await viewer.loadSkin(src, {model})`）。同步调用后立刻取帧，
   * 会拍到**还没贴上图**的模型 —— 实测表现为 `best=0`（全黑），
   * 而同时 `gl=ok blue=4096/4096` 说明渲染能力完全正常。
   *
   * 这个区别很关键：它把"渲染不通"和"拍早了"区分开了。
   */
  function nextFrame() {
    return new Promise(function (res) {
      if (typeof requestAnimationFrame === "function") {
        requestAnimationFrame(function () { res(); });
      } else {
        setTimeout(res, 16);
      }
    });
  }

  /**
   * 用浏览器原生的 `CompressionStream("deflate")` 压一段字节。
   *
   * ── 为什么需要这个 ────────────────────────────────────────────────
   * `encodePng` 为了不依赖 `canvas.toDataURL`（那个在不可见 WebView 里
   * 返回空白），手写了 PNG 编码 —— 但 zlib 部分用的是 **stored 块（不压缩）**。
   * 结果：256×320 的图 → **320KB PNG → 427KB base64**，每帧这么穿 binder。
   *
   * 真机实测（`帧耗时`）：
   *   read=17ms  encode=16ms  total=41ms  b64=427KB
   * 读取已经优化过了（pixelRatio 改成 1），**427KB 成了新的主因**。
   *
   * `CompressionStream` 是浏览器原生实现（C++ 层），比手写 JS 压缩快得多，
   * 而且能把这种大片同色的画面压到几十 KB。
   *
   * ⚠️ 它是**异步**的，所以调用方必须走 Promise ——
   * 这也是为什么 `snapshotAsync` 存在。
   *
   * @param {Uint8Array} bytes
   * @return {Promise<Uint8Array>} 压缩后的 zlib 流
   */
  function deflateRaw(bytes) {
    if (typeof CompressionStream === "undefined") {
      // 环境不支持 → 退回不压缩（旧行为），至少功能可用
      return Promise.resolve(null);
    }
    try {
      var cs = new CompressionStream("deflate");   // 产生 zlib 包装的流
      var writer = cs.writable.getWriter();
      writer.write(bytes);
      writer.close();
      return new Response(cs.readable).arrayBuffer().then(function (ab) {
        return new Uint8Array(ab);
      });
    } catch (e) {
      return Promise.resolve(null);
    }
  }

  /**
   * 把 RGBA 像素编码成 PNG data URL（不依赖 canvas.toDataURL）。
   *
   * @param {Uint8Array} rgba
   * @param {number} w
   * @param {number} h
   * @param {Uint8Array|null} zlibPrecompressed 若给了就用它当 IDAT
   *        （来自 [deflateRaw]）；否则退回 stored 块（不压缩）。
   */
  function encodePng(rgba, w, h, zlibPrecompressed) {
    // PNG 需要每行前面加一个 filter 字节（0 = None）
    var raw = new Uint8Array((w * 4 + 1) * h);
    for (var y = 0; y < h; y++) {
      raw[y * (w * 4 + 1)] = 0;
      for (var x = 0; x < w * 4; x++) {
        raw[y * (w * 4 + 1) + 1 + x] = rgba[y * w * 4 + x];
      }
    }

    // ── CRC32 ──────────────────────────────────────────────────────────
    var crcTable = (function () {
      var t = new Uint32Array(256);
      for (var n = 0; n < 256; n++) {
        var c = n;
        for (var k = 0; k < 8; k++) {
          c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        }
        t[n] = c >>> 0;
      }
      return t;
    })();

    function crc32(bytes) {
      var c = 0xffffffff;
      for (var i = 0; i < bytes.length; i++) {
        c = crcTable[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
      }
      return (c ^ 0xffffffff) >>> 0;
    }

    function chunk(type, data) {
      var len = data.length;
      var out = new Uint8Array(12 + len);
      var dv = new DataView(out.buffer);
      dv.setUint32(0, len);
      out[4] = type.charCodeAt(0);
      out[5] = type.charCodeAt(1);
      out[6] = type.charCodeAt(2);
      out[7] = type.charCodeAt(3);
      out.set(data, 8);
      var crcBuf = new Uint8Array(4 + len);
      crcBuf.set(out.subarray(4, 8 + len));
      dv.setUint32(8 + len, crc32(crcBuf));
      return out;
    }

    // zlib 数据：优先用外部压好的（CompressionStream），否则 stored 块
    var zlib;
    if (zlibPrecompressed && zlibPrecompressed.length > 0) {
      zlib = zlibPrecompressed;
    } else {
      var blocks = [];
      var pos = 0;
      var MAX = 65535;
      while (pos < raw.length) {
        var n = Math.min(MAX, raw.length - pos);
        var last = pos + n >= raw.length ? 1 : 0;
        var blk = new Uint8Array(5 + n);
        blk[0] = last;
        blk[1] = n & 0xff;
        blk[2] = (n >>> 8) & 0xff;
        blk[3] = ~n & 0xff;
        blk[4] = (~n >>> 8) & 0xff;
        blk.set(raw.subarray(pos, pos + n), 5);
        blocks.push(blk);
        pos += n;
      }
      var deflated = new Uint8Array(blocks.reduce(function (a, b) { return a + b.length; }, 0));
      var off = 0;
      blocks.forEach(function (b) { deflated.set(b, off); off += b.length; });

      var adlerA = 1, adlerB = 0;
      for (var i = 0; i < raw.length; i++) {
        adlerA = (adlerA + raw[i]) % 65521;
        adlerB = (adlerB + adlerA) % 65521;
      }
      zlib = new Uint8Array(2 + deflated.length + 4);
      zlib[0] = 0x78; zlib[1] = 0x01;
      zlib.set(deflated, 2);
      var zdv = new DataView(zlib.buffer);
      zdv.setUint32(2 + deflated.length, ((adlerB << 16) | adlerA) >>> 0);
    }

    var ihdr = new Uint8Array(13);
    var idv = new DataView(ihdr.buffer);
    idv.setUint32(0, w);
    idv.setUint32(4, h);
    ihdr[8] = 8;   // bit depth
    ihdr[9] = 6;   // color type: RGBA
    ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;

    var sig = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    var parts = [sig, chunk("IHDR", ihdr), chunk("IDAT", zlib), chunk("IEND", new Uint8Array(0))];
    var total = parts.reduce(function (a, b) { return a + b.length; }, 0);
    var png = new Uint8Array(total);
    var o2 = 0;
    parts.forEach(function (p) { png.set(p, o2); o2 += p.length; });

    // 转 base64
    var bin = "";
    var CH = 0x8000;
    for (var j = 0; j < png.length; j += CH) {
      bin += String.fromCharCode.apply(null, png.subarray(j, Math.min(j + CH, png.length)));
    }
    return "data:image/png;base64," + btoa(bin);
  }

  // ── 直显模式的自驱动渲染循环 ──────────────────────────────────────
  //
  // 离屏模式靠 Kotlin 定时调 snapshot() 取帧，每帧都要 readPixels +
  // PNG 编码 + base64 + 过 binder（实测单帧 42ms，上限 ~25fps）。
  //
  // 直显模式下画面本来就显示在屏幕上，**不需要取帧** —— 只要每帧调
  // viewer.render()，浏览器合成器就按屏幕刷新率呈现（实测 rAF 90fps）。
  //
  // 自动旋转也在这里推进：不依赖 skinview3d 内部的 draw() 循环
  //（实测那个循环在本环境下不推进，rotY 恒为 0）。
  var directLoop = null;
  var lastTick = 0;
  function startDirectLoop() {
    if (directLoop) return;
    lastTick = (typeof performance !== "undefined" && performance.now)
      ? performance.now() : Date.now();
    function frame() {
      directLoop = requestAnimationFrame(frame);
      if (!viewer) return;
      var now = (typeof performance !== "undefined" && performance.now)
        ? performance.now() : Date.now();
      var dt = Math.min((now - lastTick) / 1000, 0.05);
      lastTick = now;
      if (view.autoRotate && viewer.playerWrapper) {
        // 约 40°/秒，转一圈 9 秒 —— 与离屏模式的手动步进一致
        viewer.playerWrapper.rotation.y += dt * (40 * Math.PI / 180);
      }
      try { viewer.render(); } catch (e) {}
    }
    directLoop = requestAnimationFrame(frame);
  }
  function stopDirectLoop() {
    if (directLoop) { cancelAnimationFrame(directLoop); directLoop = null; }
  }

  window.QkSkin3D = {
    ready: function () {
      return !!viewer;
    },

    /** 初始化（幂等）。 */
    init: function () {
      return ensure();
    },

    /**
     * 加载皮肤。
     * @param {string} dataUrl 皮肤 PNG 的 data URL（null = 清空）
     * @param {string} model   'default'（经典）| 'slim'（纤细）
     */
    /**
     * 加载皮肤（**异步结果放全局，由 Kotlin 轮询取回**）。
     *
     * ⚠️⚠️ 为什么不能直接返回 Promise —— 真机实测踩到：
     *
     * `WebView.evaluateJavascript()` **只支持同步返回值**。
     * 传回一个 Promise 时，它拿到的是 `{}`（序列化后的空对象），
     * 不是"等一下再给你结果"。自检日志当时打印的就是 `页面自检: {}`。
     *
     * 所以异步结果必须换个通道。这里用**全局变量 + 轮询**：
     *   - JS 把结果写进 `window.__QK_SKIN3D_RESULT`
     *   - Kotlin 隔一小段时间 evaluateJavascript 读这个变量
     *
     * 为什么不用 bridge 回调：`__QK_BRIDGE__` 是 Rust 的 Tauri IPC，
     * 要为它注册新命令就得改 Rust（违反 0 改动约束）。
     * 轮询虽然朴素，但零依赖、可观测（每次读都能打日志）。
     *
     * @return {boolean} 是否**已受理**（不代表已完成）
     */
    load: function (dataUrl, model) {
      window.__QK_SKIN3D_RESULT = "";
      if (!ensure()) {
        window.__QK_SKIN3D_RESULT = "noinit";
        return false;
      }
      currentDataUrl = dataUrl || null;
      currentModel = model === "slim" ? "slim" : "default";
      // 后发起者胜：旧请求的 Promise 回来时发现序号过期，直接丢弃。
      var mySeq = ++loadSeq;
      try {
        if (!currentDataUrl) {
          viewer.loadSkin(null);
          nextFrame().then(function () {
            if (mySeq !== loadSeq) return;
            QkSkin3D.snapshotAsync(SNAP_W, SNAP_H);
          });
          return true;
        }
        // ⚠️ 先清空再带 model 重新加载 —— skinview3d 对**同一个 source**
        // 会直接早退、不重建几何体，于是 slim/classic 的胳膊宽度不会变。
        // 旧 UI 的 useSkinRenderer.ts 也是这么处理的。
        //
        // ⚠️⚠️ 但「清空」和「重载」之间**必须等一帧**（2026-10-22 修）：
        // `loadSkin(null)` 会 resetSkin()（map=null / visible=false），
        // 如果紧接着同步调 `loadSkin(url)`，两次操作落在同一帧里，
        // `recreateSkinTexture()` 与 `modelType` setter 的几何体重建
        // 顺序不定 —— 表现就是「手臂宽度不对」或「整块绿」。
        viewer.loadSkin(null);
        nextFrame()
          .then(function () {
            if (mySeq !== loadSeq) return null;
            // 纹理真的加载完（Promise resolve）才算就绪
            return Promise.resolve(
              viewer.loadSkin(currentDataUrl, { model: currentModel })
            );
          })
          .then(nextFrame)
          .then(function () {
            if (mySeq !== loadSeq) return;
            QkSkin3D.snapshotAsync(SNAP_W, SNAP_H);
          })
          .catch(function (e) {
            if (mySeq !== loadSeq) return;
            window.__QK_SKIN3D_RESULT =
              "loaderr:" + (e && e.message ? e.message : e);
          });
        return true;
      } catch (e) {
        window.__QK_SKIN3D_RESULT =
          "loaderr:" + (e && e.message ? e.message : e);
        return false;
      }
    },

    setAnimation: function (name) {
      currentAnim = name || "none";
      applyAnimation();
      return true;
    },

    setModel: function (model) {
      currentModel = model === "slim" ? "slim" : "default";
      if (!viewer || !currentDataUrl) return false;
      var mySeq = ++loadSeq;
      try {
        // 同上：强制重建几何体，且**清空与重载之间等一帧**，
        // 否则手臂宽度与贴图会错配（2026-10-22 用户报的手臂不变/绿块）。
        viewer.loadSkin(null);
        nextFrame()
          .then(function () {
            if (mySeq !== loadSeq) return null;
            return Promise.resolve(
              viewer.loadSkin(currentDataUrl, { model: currentModel })
            );
          })
          .then(nextFrame)
          .then(function () {
            if (mySeq !== loadSeq) return;
            QkSkin3D.snapshotAsync(SNAP_W, SNAP_H);
          })
          .catch(function () { });
        return true;
      } catch (e) {
        return false;
      }
    },

    setView: function (yawDeg, pitchDeg, zoom) {
      view.yaw = yawDeg || 0;
      view.pitch = pitchDeg || 0;
      if (typeof zoom === "number" && zoom > 0) view.zoom = zoom;
      applyView();
      return true;
    },

    /**
     * 拖拽旋转（Kotlin 把手势位移转成像素传进来）。
     *
     * 见 `orbitBy` 的注释：走 OrbitControls，不手改相机。
     */
    orbit: function (dx, dy) {
      return orbitBy(dx, dy);
    },

    /**
     * 自检：**验证旋转接口真的改变了画面**。
     *
     * "接口存在" ≠ "调用有效" —— 之前 `setView` 手改相机的写法
     * 函数存在、返回 true，但画面是全黑的。所以这里比对旋转前后的像素。
     *
     * @return {string} "orbit=ok:changed" 或失败原因
     */
    verifyOrbit: function () {
      if (!ensure()) return "noinit";
      try {
        // 用内置皮肤，保证画面上有东西可转
        var c = document.createElement("canvas");
        c.width = 64; c.height = 64;
        var g = c.getContext("2d");
        g.fillStyle = "#4ec9a0"; g.fillRect(0, 0, 64, 64);
        g.fillStyle = "#1f4d3d";
        g.fillRect(8, 0, 8, 8);
        g.fillRect(20, 20, 8, 12);
        var url = c.toDataURL("image/png");

        viewer.loadSkin(null);
        viewer.loadSkin(url, { model: "default" });

        // 不能 await（evaluateJavascript 不支持 Promise），所以用同步顺序：
        // loadSkin 已提交，orbit 作用在同一场景上，两次 snapshot 比对。
        var before = QkSkin3D.snapshot(64, 64);
        var ok1 = orbitBy(120, 0);   // 横向拖 120px
        var after = QkSkin3D.snapshot(64, 64);

        if (!before || !after) return "nosnap";
        // 直接比 base64 字符串：画面变了，编码结果必然不同
        var changed = before !== after;
        return "orbit=" + (ok1 && changed ? "ok:changed" : "nochange") +
               " len=" + before.length + "/" + after.length;
      } catch (e) {
        return "orbitErr:" + (e && e.message ? e.message : e);
      }
    },

    /**
     * 自检：**验证自动旋转真的在转**。
     *
     * 原理同 [verifyOrbit]：开自动旋转 → 手动推进几帧 → 比对画面是否变化。
     *
     * ⚠️ 为什么要手动推进：skinview3d 的 `autoRotate` 靠它**自己的渲染循环**
     * 累积 `playerWrapper.rotation.y`。而我们用的是离屏按需渲染，
     * 没有那个循环 —— 所以要在这里显式调 `viewer.render()` 若干次，
     * 模拟"经过了几帧"，再看模型角度是否真的变了。
     *
     * 这也解释了为什么 Kotlin 侧必须定时调 `snapshot()`：
     * 不推进就不会转。
     *
     * @return {string} "auto=ok:turned" / "auto=nochange" / 错误
     */
    verifyAutoRotate: function () {
      if (!ensure()) return "noinit";
      try {
        var c = document.createElement("canvas");
        c.width = 64; c.height = 64;
        var g = c.getContext("2d");
        g.fillStyle = "#4ec9a0"; g.fillRect(0, 0, 64, 64);
        g.fillStyle = "#1f4d3d";
        g.fillRect(8, 0, 8, 8);
        var url = c.toDataURL("image/png");

        viewer.loadSkin(null);
        viewer.loadSkin(url, { model: "default" });

        // 用「模型角度是否真的变了」来判定，比像素比对直接。
        var y0 = viewer.playerWrapper ? viewer.playerWrapper.rotation.y : null;
        if (y0 === null) return "noPlayerWrapper";

        QkSkin3D.setAutoRotate(true);
        // 模拟 Kotlin 侧的定时推进：连推 20 步
        for (var i = 0; i < 20; i++) QkSkin3D.stepAutoRotate(6);
        var y1 = viewer.playerWrapper.rotation.y;

        QkSkin3D.setAutoRotate(false);
        var moved = Math.abs(y1 - y0);

        return "auto=" + (moved > 0.01 ? "ok:turned" : "nochange") +
               " dY=" + moved.toFixed(3) +
               " (" + y0.toFixed(3) + "→" + y1.toFixed(3) + ")";
      } catch (e) {
        return "autoErr:" + (e && e.message ? e.message : e);
      }
    },

    /**
     * 探测可用的压缩能力（一次性）。
     *
     * 我的 PNG 编码器用的是 zlib **stored 块（不压缩）** —— 每像素 4 字节
     * 原样写入，所以 256×320 会产出 ~320KB PNG / 427KB base64。
     * 这是回传慢的主因。
     *
     * 浏览器原生提供 `CompressionStream("deflate")`，用它压缩后
     * PNG 能小一个数量级（有损的 JPEG 则更小，但需要另写编码器）。
     */
    probeCompression: function () {
      var hasCS = (typeof CompressionStream !== "undefined");
      return "CompressionStream=" + (hasCS ? "yes" : "no") +
             " devicePixelRatio=" + (window.devicePixelRatio || 1);
    },

    /**
     * **压缩版取帧**（异步）—— 结果写进 `__QK_SKIN3D_RESULT`，由 Kotlin 轮询取回。
     *
     * ── 为什么需要它（性能）────────────────────────────────────────────
     * 同步版 `snapshot()` 为绕开 `canvas.toDataURL` 手写了 PNG 编码，
     * 但 zlib 部分用的是 **stored 块（完全不压缩）** —— 每像素 4 字节原样写入。
     * 真机实测：
     *
     *     read=17ms  encode=16ms  total=41ms  b64=427KB
     *
     * 读取已经优化过（`pixelRatio: 1` 把 src 从 900×1125 降到 320×400），
     * **427KB 成了新的主因**：150ms 一帧 = 每秒 2.8MB 穿过 binder，
     * 而且 Kotlin 侧还要 base64 解码 + 位图解码。
     *
     * 这里改用浏览器原生的 `CompressionStream("deflate")`（C++ 实现，
     * 比手写 JS 压缩快得多），这种大片同色的画面能压到几十 KB。
     *
     * ⚠️ 异步 → 不能直接 return（`evaluateJavascript` 不支持 Promise），
     * 所以复用"写全局 + 轮询"那套（和 `load` 一样）。
     */
    snapshotAsync: function (outW, outH) {
      window.__QK_SKIN3D_RESULT = "";   // 清空，避免读到上一帧
      try {
        // 先同步取像素（复用 snapshot 的逻辑，它会顺手把像素留在全局）
        var sync = QkSkin3D.snapshot(outW, outH);
        if (!sync) {
          window.__QK_SKIN3D_RESULT = "nosnap";
          return false;
        }
        var px = window.__QK_SKIN3D_LAST_PIXELS;
        var w = window.__QK_SKIN3D_LAST_W;
        var h = window.__QK_SKIN3D_LAST_H;
        if (!px) {
          // 拿不到像素就退回同步结果（至少能用）
          window.__QK_SKIN3D_RESULT = sync;
          return true;
        }

        // 加 PNG 的行过滤字节（filter=0/None），再交给 CompressionStream
        var raw = new Uint8Array((w * 4 + 1) * h);
        for (var y = 0; y < h; y++) {
          raw[y * (w * 4 + 1)] = 0;
          for (var x = 0; x < w * 4; x++) {
            raw[y * (w * 4 + 1) + 1 + x] = px[y * w * 4 + x];
          }
        }

        deflateRaw(raw).then(function (zlibBytes) {
          if (!zlibBytes || zlibBytes.length === 0) {
            window.__QK_SKIN3D_RESULT = sync;   // 压缩不可用 → 用同步结果
            return;
          }
          var t0 = (typeof performance !== "undefined" && performance.now)
            ? performance.now() : Date.now();
          var out = encodePng(px, w, h, zlibBytes);
          var perf = window.__QK_SKIN3D_PERF || {};
          perf.b64 = out ? out.length : 0;
          perf.mode = "deflate";
          window.__QK_SKIN3D_PERF = perf;
          window.__QK_SKIN3D_RESULT = out || "nosnap";
        }).catch(function () {
          window.__QK_SKIN3D_RESULT = sync;
        });
        return true;
      } catch (e) {
        window.__QK_SKIN3D_RESULT = "asyncerr:" + (e && e.message ? e.message : e);
        return false;
      }
    },

    /**
     * **推送版取帧** —— 渲染完直接调 Kotlin 的原生接口，不轮询。
     *
     * ── 为什么要这个（性能）────────────────────────────────────────────
     * "写全局 + Kotlin 轮询"那套实测每帧多花 **~27ms**：
     * evaluateJavascript 发起一次、隔 POLL_MS 再读一次，两次 binder 往返。
     * 而 JS 侧渲染+编码只用了 15ms。
     *
     * 页面里已经有 `QookiXNative`（`addJavascriptInterface` 注入的原生桥），
     * 加一个 `onSkinFrame(dataUrl)` 就是零往返的推送通道 ——
     * 不新增 WebView、不碰 Rust 命令表。
     *
     * 推不动时（接口不存在）自动退回全局变量，保证功能不丢。
     */
    snapshotPush: function (outW, outH) {
      window.__QK_SKIN3D_RESULT = "";
      try {
        var sync = QkSkin3D.snapshot(outW, outH);
        if (!sync) {
          window.__QK_SKIN3D_RESULT = "nosnap";
          return false;
        }
        var px = window.__QK_SKIN3D_LAST_PIXELS;
        var w = window.__QK_SKIN3D_LAST_W;
        var h = window.__QK_SKIN3D_LAST_H;
        var native = window.QookiXNative;
        var canPush = native && typeof native.onSkinFrame === "function";
        if (!px) {
          if (canPush) { try { native.onSkinFrame(sync); return true; } catch (e) {} }
          window.__QK_SKIN3D_RESULT = sync;
          return true;
        }

        var raw = new Uint8Array((w * 4 + 1) * h);
        for (var y = 0; y < h; y++) {
          raw[y * (w * 4 + 1)] = 0;
          for (var x = 0; x < w * 4; x++) {
            raw[y * (w * 4 + 1) + 1 + x] = px[y * w * 4 + x];
          }
        }

        deflateRaw(raw).then(function (zlibBytes) {
          var png;
          if (zlibBytes && zlibBytes.length > 0) {
            png = encodePng(px, w, h, zlibBytes);
            var perf = window.__QK_SKIN3D_PERF || {};
            perf.b64 = png ? png.length : 0;
            perf.mode = "deflate";
            window.__QK_SKIN3D_PERF = perf;
          } else {
            png = sync;
          }
          if (!png) png = "nosnap";
          // ⚠️ 推送和写全局**都做** —— 不能推送完就 return。
          // 之前只推送，而 `loadSkin` 那条路是**轮询全局变量**的，
          // 于是帧推给了没人认领的 sink、轮询方永远等不到 → 卡死在
          // 「正在渲染 3D…」。两条通道都填，由 Kotlin 侧的单次令牌
          // 保证只消费一个。
          window.__QK_SKIN3D_RESULT = png;
          if (canPush) {
            try { native.onSkinFrame(png); } catch (e) { /* 全局已写，轮询会兜住 */ }
          }
        }).catch(function () {
          window.__QK_SKIN3D_RESULT = sync;
          if (canPush) { try { native.onSkinFrame(sync); } catch (e) {} }
        });
        return true;
      } catch (e) {
        window.__QK_SKIN3D_RESULT = "pusherr:" + (e && e.message ? e.message : e);
        return false;
      }
    },

    /**
     * 测这个 WebView 的**真实渲染帧率**（不取像素，只跑 rAF 循环）。
     *
     * ── 为什么先测这个 ────────────────────────────────────────────────
     * "让 WebView 可见就能 60fps" 是个**推断**。在动 wry 的视图树
     *（那个 WebView 承载全部 153 个后端命令的 IPC，动坏了很麻烦）之前，
     * 得先知道它到底能跑多少帧。
     *
     * 这个测试只做两件事：跑 rAF、累加计数。不 readPixels、不编码、
     * 不过 binder —— 量出来的就是**这个不可见 WebView 的帧回调能力**。
     *
     * ⚠️ 注意：这是"不可见"状态下的数字。不可见时浏览器会**节流 rAF**
     *（通常降到 1fps 甚至暂停）。所以这个结果只能说明
     * "当前够不够用"，不能直接推断"可见后能到多少"。
     *
     * @param {number} ms 采样时长
     * @return {string} 采样结果（异步写在 __QK_SKIN3D_FPS）
     */
    measureFps: function (ms) {
      var dur = ms || 1000;
      window.__QK_SKIN3D_FPS = "measuring";
      var t0 = (typeof performance !== "undefined" && performance.now)
        ? performance.now() : Date.now();
      var frames = 0;
      var self = this;
      function tick() {
        frames++;
        var now = (typeof performance !== "undefined" && performance.now)
          ? performance.now() : Date.now();
        if (now - t0 >= dur) {
          window.__QK_SKIN3D_FPS =
            "rAF " + frames + " 帧 / " + Math.round(now - t0) + "ms = " +
            (frames * 1000 / (now - t0)).toFixed(1) + "fps" +
            " visibility=" + (document.visibilityState || "?");
          return;
        }
        // 顺便渲染一帧，模拟真实负载
        try { if (viewer) viewer.render(); } catch (e) {}
        requestAnimationFrame(tick);
      }
      requestAnimationFrame(tick);
      return "started";
    },

    /**
     * 切换「直接显示」模式。
     *
     * ── 两种模式的区别 ────────────────────────────────────────────────
     *   离屏回传（默认）：canvas 放在屏幕外，每帧 readPixels + 编码 +
     *                     base64 + 过 binder 交给 Compose 显示。22–25fps。
     *   直接显示：canvas **铺满可视区**，由浏览器自己合成到屏幕上。
     *             不取像素、不过 binder —— 实测这个 WebView 的 rAF
     *             本来就能跑 90fps，所以这里能拿到 ~60fps。
     *
     * 切换时把 canvas 从 `left:-10000px` 挪回可视区（或反过来）。
     *
     * @param {boolean} on
     * @param {number} w 可视区宽（CSS 像素）
     * @param {number} h 可视区高
     */
    /**
     * 切换「直接显示」模式。
     *
     * @param {boolean} on
     * @param {number} wpx 预览区**物理像素**宽（Kotlin 传来的 LayoutCoordinates.size）
     * @param {number} hpx 预览区**物理像素**高
     */
    setDirectDisplay: function (on, wpx, hpx) {
      if (!ensure()) return false;
      try {
        if (on) {
          // WebView 的 CSS 视口 = 物理像素 / devicePixelRatio。
          // canvas 要按 CSS 尺寸铺满，否则要么溢出要么留白。
          var dpr = window.devicePixelRatio || 1;
          var cw = Math.max(1, Math.round((wpx || 200) / dpr));
          var chh = Math.max(1, Math.round((hpx || 250) / dpr));
          canvas.style.position = "absolute";
          canvas.style.left = "0";
          canvas.style.top = "0";
          canvas.style.width = cw + "px";
          canvas.style.height = chh + "px";
          canvas.style.zIndex = "9999";
          // 让页面本身铺满、背景透明（露出 Compose 的圆角框底色）
          document.documentElement.style.background = "transparent";
          document.body.style.background = "transparent";
          document.body.style.margin = "0";
          // setSize 接收的是 CSS 逻辑尺寸（内部会再乘 pixelRatio）
          if (viewer && viewer.setSize) viewer.setSize(cw, chh);
          view.direct = true;
          startDirectLoop();
        } else {
          stopDirectLoop();
          canvas.style.position = "fixed";
          canvas.style.left = "-10000px";
          canvas.style.top = "0";
          canvas.style.width = "";
          canvas.style.height = "";
          canvas.style.zIndex = "";
          if (viewer && viewer.setSize) viewer.setSize(SNAP_W, SNAP_H);
          view.direct = false;
        }
        return true;
      } catch (e) {
        return false;
      }
    },

    /**
     * 性能诊断：返回最近一帧的各段耗时。
     *
     * 卡顿问题光看"感觉"没法优化 —— 要能说清是渲染慢、读取慢还是编码慢。
     * 用法：连拖几下预览，然后在 logcat 看这个字符串。
     */
    perf: function () {
      var p = window.__QK_SKIN3D_PERF;
      if (!p) return "noperf";
      return "read=" + p.read + "ms encode=" + p.encode + "ms total=" + p.total +
             "ms src=" + p.srcW + "x" + p.srcH + " out=" + p.outW + "x" + p.outH +
             " b64=" + Math.round(p.b64 / 1024) + "KB mode=" + (p.mode || "?");
    },

    /**
     * 自动旋转开关。
     *
     * ── ⚠️⚠️ 为什么不能只用 `viewer.autoRotate` ──────────────────────
     * skinview3d 内部是这样转的（bundle 里 `draw()`）：
     *
     *     draw() {
     *       let e = this.clock.getDelta();
     *       ...
     *       this.autoRotate && !userRotating && (playerWrapper.rotation.y += e * speed),
     *       this.render();
     *       this.animationID = requestAnimationFrame(() => this.draw())   // 自我续帧
     *     }
     *
     * 也就是说它**依赖 requestAnimationFrame 循环**。
     * 但我们跑在一个**不可见的 WebView** 里 —— 实测这个环境下 rAF 不推进
     *（真机自检 `rotY=0.000` 就是证据：开了 autoRotate 也不动）。
     *
     * 所以这里**自己接管**：由 `stepAutoRotate()` 在每次取帧前手动推进一步。
     * Kotlin 侧本来就要定时调 `snapshot()`，顺势调这个即可 ——
     * 不依赖浏览器的帧回调，也不依赖 skinview3d 的 clock。
     */
    setAutoRotate: function (on) {
      view.autoRotate = !!on;
      applyView();
      // 双保险：如果环境里 rAF 正常，skinview3d 自己也会转；
      // 不正常则由 stepAutoRotate 兜底。两者叠加会转得快些，
      // 所以把内置的那个关掉，统一由我们控制速度。
      try { if (viewer) viewer.autoRotate = false; } catch (e) {}
      return true;
    },

    /**
     * 手动推进一步自动旋转（由 Kotlin 在取帧前调用）。
     *
     * @param {number} deg 本次转过的角度（度）
     */
    stepAutoRotate: function (deg) {
      if (!viewer || !viewer.playerWrapper) return false;
      if (!view.autoRotate) return false;
      try {
        var rad = (deg || 6) * Math.PI / 180;
        viewer.playerWrapper.rotation.y += rad;
        return true;
      } catch (e) {
        return false;
      }
    },

    /**
     * 加载/卸下披风。
     *
     * @param {string|null} dataUrl 披风 PNG 的 data URL；null = 卸下
     */
    loadCape: function (dataUrl) {
      if (!ensure()) return false;
      try {
        if (dataUrl) {
          // loadCape 也是异步的（同 loadSkin），但披风不影响首屏，
          // 失败也只是没披风，所以不阻塞调用方。
          Promise.resolve(viewer.loadCape(dataUrl)).catch(function () {});
        } else {
          viewer.loadCape(null);
        }
        return true;
      } catch (e) {
        return false;
      }
    },

    reset: function () {
      view.yaw = 0; view.pitch = 0; view.zoom = 0.9;
      // 视角重置交给 skinview3d 自己的实现 ——
      // 不要手写 camera.position（那会把画面搞成全黑，见 applyView 注释）
      try { if (viewer && viewer.resetCameraPose) viewer.resetCameraPose(); } catch (e) {}
      applyView();
      return true;
    },

    /**
     * 自检用：用一张**内置的小皮肤**跑完整管线，报告内容占比。
     *
     * 为什么要内置而不是用用户皮肤：启动自检时皮肤库可能是空的，
     * 而"管线能不能出画面"这件事需要在任何情况下都能验证。
     *
     * @return {string} 形如 "ok:1234/4096"（非背景像素/总像素）
     */
    selfTest: function () {
      try {
        if (!ensure()) return "noinit";

        // ── 先做一次"能不能画"的最小验证 ────────────────────────────────
        //
        // 排查"画面全黑"时必须先分清：是**渲染管线不通**，还是
        // **模型/相机/读取目标**的问题。
        //
        // 这里用最朴素的方式画一个纯色块：不经 three.js、不经 composer，
        // 直接用原生 GL 画进自建 FBO 再读回。
        // 如果连这个都读不到，说明这个不可见 WebView 根本不产生像素，
        // 那么 three.js 层面再怎么调都是白费。
        // 注：早期这里跑过一个"原生 GL 能不能画出像素"的探针，
        // 它证明了不可见 WebView 的渲染能力正常（gl=ok blue=4096/4096），
        // 从而把问题范围缩小到 three.js 这一层。结论拿到后已删除 ——
        // 它自己会把缓冲 clear 成蓝色，留着会污染真实画面。

        // 一张 64×64 的纯色皮肤（含一点深色，保证有可见内容）
        var c = document.createElement("canvas");
        c.width = 64; c.height = 64;
        var g = c.getContext("2d");
        g.fillStyle = "#4ec9a0";
        g.fillRect(0, 0, 64, 64);
        g.fillStyle = "#1f4d3d";
        g.fillRect(8, 0, 8, 8);     // 头发
        g.fillRect(20, 20, 8, 12);  // 身体
        var url = c.toDataURL("image/png");
        if (!url || url.indexOf("data:image/png") !== 0) return "noimg";

        viewer.loadSkin(null);
        // ⚠️ 结果写全局、由 Kotlin 轮询取回 —— 见 load() 里关于
        // "evaluateJavascript 不支持 Promise 返回值"的注释。
        window.__QK_SKIN3D_SELFTEST = "pending";
        Promise.resolve(viewer.loadSkin(url, { model: "default" }))
          .then(nextFrame)
          .then(function () {
            var out = QkSkin3D.snapshot(64, 64);
            var ok = !!(out && out.indexOf("data:image/png") === 0);
            // 探针已移除：它的作用是证明"这个不可见 WebView 能产生像素"，
            // 结论已经拿到（gl=ok blue=4096/4096）。留着反而有害 ——
            // 它自己 clear 成蓝色，会污染后续的画面读取。
            window.__QK_SKIN3D_SELFTEST = (ok ? "ok:" : "bad:") +
              window.__QK_SKIN3D_LAST_OPAQUE + "/" + window.__QK_SKIN3D_LAST_TOTAL +
              " " + (window.__QK_SKIN3D_LAST_DEBUG || "nodb");
          })
          .catch(function (e) {
            window.__QK_SKIN3D_SELFTEST = "err:" + (e && e.message ? e.message : e);
          });
        return "pending";
      } catch (e) {
        return "err:" + (e && e.message ? e.message : e);
      }
    },

    /**
     * 取当前帧。
     *
     * 流程：readPixels 读 RGBA → 手工编 PNG → base64。
     * **不用 canvas.toDataURL** —— 实测在那个不可见 WebView 里返回空白。
     *
     * @param {number} outW 输出宽度（等比缩放到 outH）
     * @param {number} outH 输出高度
     * @return {string} data URL，失败返回空串
     */
    snapshot: function (outW, outH) {
      if (!ensure() || !viewer) return "";
      // 计时（性能诊断用）—— 卡顿时靠它定位是哪一段慢。
      var t0 = (typeof performance !== "undefined" && performance.now)
        ? performance.now() : Date.now();
      var tRender = 0, tRead = 0, tEncode = 0;
      try {
        var r = viewer.renderer;
        var scene = viewer.scene;
        var cam = viewer.camera;
        if (!r || !scene || !cam) return "";
        var gl = r.getContext ? r.getContext() : null;
        if (!gl) return "";

        var sw = canvas.width;
        var sh = canvas.height;

        // ── ⚠️⚠️ 取图必须走 render target，不能读默认帧缓冲 ──────────────
        //
        // 真机实测在这条路上踩了三次，每次都返回**全黑图**：
        //
        //   ① `viewer.render()`（走内部 EffectComposer）→ 读默认缓冲 → 全 0
        //      （composer 把画面留在离屏 FBO，默认缓冲是空的）
        //   ② 绕开 composer 直接 `renderer.render(scene, cam)` → 立刻读 → 仍全 0
        //      （帧提交后驱动不再保证默认缓冲内容，preserveDrawingBuffer 在
        //        WebView 实现里不一定被遵守）
        //   ③ 手建裸 FBO 渲染 → 相机/场景都正常（cam=0,0,43.8 children=3
        //      pwVis=true）但读出来还是 0（three 内部的 renderTarget 状态
        //      没同步，它按默认缓冲的视口去画）
        //
        // 正解：用 **three 自己的 render target + readRenderTargetPixels** ——
        // 这是 three 为"离屏读取"提供的标准路径，会把内部状态一并处理好。
        //
        // `composer.renderTarget1` 就是 skinview3d 已经在用的那个 target，
        // 复用它无需新建，尺寸/格式都对得上。
        // 试着从 composer 的两个 render target 里各读一次，取内容多的那个。
        // 为什么两个都要读：EffectComposer 是**双缓冲**，最后一趟 pass
        // 可能落在 renderTarget1 也可能是 renderTarget2，取决于 pass 数量。
        var px = new Uint8Array(sw * sh * 4);
        var best = -1, bestName = "none";
        var targets = [
          ["rt1", viewer.composer && viewer.composer.renderTarget1],
          ["rt2", viewer.composer && viewer.composer.renderTarget2],
        ];
        try { viewer.render(); } catch (e) {}
        for (var ti = 0; ti < targets.length; ti++) {
          var name = targets[ti][0], rt = targets[ti][1];
          if (!rt || typeof r.readRenderTargetPixels !== "function") continue;
          var tw = rt.width || sw, th = rt.height || sh;
          var buf = new Uint8Array(tw * th * 4);
          try { r.readRenderTargetPixels(rt, 0, 0, tw, th, buf); } catch (e) { continue; }
          var n = 0;
          for (var k = 0; k < buf.length; k += 4) {
            if (buf[k + 3] > 8 && (buf[k] > 8 || buf[k + 1] > 8 || buf[k + 2] > 8)) n++;
          }
          if (n > best) {
            best = n; bestName = name + tw + "x" + th;
            var cw = Math.min(tw, sw), ch = Math.min(th, sh);
            for (var yy = 0; yy < ch; yy++) {
              var so = yy * tw * 4;
              px.set(buf.subarray(so, so + cw * 4), yy * sw * 4);
            }
          }
        }
        if (best < 0) {
          // 没有可用的 target → 兜底走默认缓冲
          if (typeof r.setRenderTarget === "function") r.setRenderTarget(null);
          r.render(scene, cam);
          gl.readPixels(0, 0, sw, sh, gl.RGBA, gl.UNSIGNED_BYTE, px);
          bestName = "default";
        }
        window.__QK_SKIN3D_READPATH = bestName + "(best=" + best + ")";

        // ── 性能打点（diagnoseSpin）──────────────────────────────────
        // 卡顿时要能说清是"渲染慢"还是"读取慢"还是"编码慢"。
        var nowFn = (typeof performance !== "undefined" && performance.now)
          ? function () { return performance.now(); }
          : function () { return Date.now(); };
        tRead = nowFn() - t0;

        // 诊断：统计"非背景色"的像素比例。
        //
        // 为什么需要：管线通了（能返回合法 PNG）不等于**画面有内容** ——
        // 相机没对准模型、或缓冲没画上东西时，返回的是一张全黑图，
        // 从"有没有返回 data URL"是看不出来的。
        var nonEmpty = 0;
        for (var i = 0; i < px.length; i += 4) {
          if (px[i + 3] > 8 && (px[i] > 8 || px[i + 1] > 8 || px[i + 2] > 8)) nonEmpty++;
        }
        window.__QK_SKIN3D_LAST_OPAQUE = nonEmpty;
        window.__QK_SKIN3D_LAST_TOTAL = sw * sh;

        // 更细的诊断：canvas 尺寸 / 若干采样点。
        // 全黑时靠这几项区分是"没画上去"还是"读了错误的缓冲"。
        var s1 = px[0] + "," + px[1] + "," + px[2] + "," + px[3];
        var mid = (Math.floor(sh / 2) * sw + Math.floor(sw / 2)) * 4;
        var s2 = px[mid] + "," + px[mid + 1] + "," + px[mid + 2] + "," + px[mid + 3];

        // 场景/相机状态 —— 全黑时用来区分"相机没对准"还是"根本没画"。
        var camInfo = "nocam", sceneInfo = "noscene", skinVisible = "?";
        try {
          if (cam) {
            camInfo = "cam=" +
              cam.position.x.toFixed(1) + "," +
              cam.position.y.toFixed(1) + "," +
              cam.position.z.toFixed(1);
          }
          if (scene) sceneInfo = "children=" + scene.children.length;
          if (scene) sceneInfo = "children=" + scene.children.length;
          // 角色模型是 playerWrapper（不是 viewer.skin —— 那是纹理源）
          if (viewer.playerWrapper) {
            var pw = viewer.playerWrapper;
            // 找有几何体的子对象，报告顶点数与材质是否带贴图。
            // 全黑时用来区分"模型没建出来"和"建了但没画上"。
            var meshes = [];
            pw.traverse(function (o) {
              if (o.isMesh || o.geometry) {
                var vc = (o.geometry && o.geometry.attributes &&
                          o.geometry.attributes.position)
                  ? o.geometry.attributes.position.count : 0;
                var hasMap = !!(o.material && o.material.map);
                meshes.push((o.isMesh ? "M" : "G") + vc + (hasMap ? "+tex" : ""));
              }
            });
            skinVisible = "pwVis=" + pw.visible +
              " kids=" + pw.children.length +
              " meshes=[" + meshes.slice(0, 6).join(",") + "]";
          } else {
            skinVisible = "pw=null";
          }
        } catch (e) { camInfo = "err"; }

        window.__QK_SKIN3D_LAST_DEBUG =
          "canvas=" + sw + "x" + sh +
          " corner=" + s1 + " center=" + s2 +
          " " + camInfo + " " + sceneInfo + " " + skinVisible + " path=" + (window.__QK_SKIN3D_READPATH || "?");

        // WebGL 的原点在**左下**，PNG 在**左上** —— 必须垂直翻转，
        // 否则人物是倒的。
        var flipped = new Uint8Array(sw * sh * 4);
        var rowBytes = sw * 4;
        for (var y = 0; y < sh; y++) {
          var srcOff = (sh - 1 - y) * rowBytes;
          flipped.set(px.subarray(srcOff, srcOff + rowBytes), y * rowBytes);
        }

        // 需要缩放时用 2D canvas 的 drawImage 做（它本身可用，只是 toDataURL 不行）
        var out = flipped;
        var ow = sw, oh = sh;
        if (outW && outH && (outW !== sw || outH !== sh)) {
          var tmp = document.createElement("canvas");
          tmp.width = sw; tmp.height = sh;
          var tctx = tmp.getContext("2d");
          var id = tctx.createImageData(sw, sh);
          id.data.set(flipped);
          tctx.putImageData(id, 0, 0);

          var tmp2 = document.createElement("canvas");
          tmp2.width = outW; tmp2.height = outH;
          var t2 = tmp2.getContext("2d");
          t2.drawImage(tmp, 0, 0, outW, outH);
          var id2 = t2.getImageData(0, 0, outW, outH);
          out = new Uint8Array(id2.data.buffer.slice(0));
          ow = outW; oh = outH;
        }

        // ── 编码耗时打点 ─────────────────────────────────────────────
        var _tEnc0 = (typeof performance !== "undefined" && performance.now)
          ? performance.now() : Date.now();
        var _png = encodePng(out, ow, oh);
        var _tEnc1 = (typeof performance !== "undefined" && performance.now)
          ? performance.now() : Date.now();
        window.__QK_SKIN3D_PERF = {
          read: Math.round(tRead),
          encode: Math.round(_tEnc1 - _tEnc0),
          total: Math.round(_tEnc1 - t0),
          srcW: sw, srcH: sh, outW: ow, outH: oh,
          b64: _png ? _png.length : 0,
          mode: "stored"
        };
        // 顺手把这一帧的像素留在全局，供 snapshotAsync 复用（避免重读一遍）
        window.__QK_SKIN3D_LAST_PIXELS = out;
        window.__QK_SKIN3D_LAST_W = ow;
        window.__QK_SKIN3D_LAST_H = oh;
        return _png;
      } catch (e) {
        console.log("[qk-skin3d] snapshot 失败: " + (e && e.message ? e.message : e));
        return "";
      }
    },
  };

  console.log("[qk-skin3d] 宿主已就绪");

})();








