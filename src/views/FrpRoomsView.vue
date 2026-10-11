<!--
  XTCP 联机房间。

  和「FRP 隧道」页的区别（这也是我把它们分成两页的原因）：
    - 隧道页是 **TCP 直连**：只有房主跑 frpc，把本地端口映射成 `节点:远端端口`，
      然后把那个地址发出去 —— **客人用原版 Minecraft 或任意启动器直接连就行**；
    - 这一页是 **XTCP 房间**：房主跑一条 xtcp「代理」、访问者跑一条 xtcp「访问器」，
      **双方都得跑 frpc**，客人最终连的是自己机器上的 `127.0.0.1:<bindPort>`。

  所以房主看到的按钮是「启动房间」、别人看到的是「加入并连接」，两者跑的是不同的配置。
  绑定端口按 `localPort + 10000 + uid%1000` 算（与官网客户端一致，算错就连不上）。
-->
<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../api";
import { t as $t } from "../i18n";
import { useMessage } from "../composables/message";
import AppSheet from "../ui/AppSheet.vue";

const message = useMessage();

type Room = Awaited<ReturnType<typeof api.frpRoomList>>["rooms"][number];
type RoomNode = Awaited<ReturnType<typeof api.frpRoomNodes>>[number];

const rooms = ref<Room[]>([]);
const nodes = ref<RoomNode[]>([]);
const busy = ref(false);
/** 每条房间自己的提示（例如「连 127.0.0.1:35xxx」） */
const notes = ref<Record<number, string>>({});

const createShow = ref(false);
const newRoom = ref({ name: "", localPort: 25565, password: "", nodeId: 0 });

async function refresh() {
  busy.value = true;
  try {
    const r = await api.frpRoomList(1);
    rooms.value = r.rooms ?? [];
    if (!nodes.value.length) {
      nodes.value = await api.frpRoomNodes();
      const online = nodes.value.find((n) => n.online) ?? nodes.value[0];
      if (online) newRoom.value.nodeId = online.id;
    }
  } catch (e) {
    message.error(String(e));
  } finally {
    busy.value = false;
  }
}

async function create() {
  if (!newRoom.value.name.trim()) {
    message.error($t("rooms.name-required"));
    return;
  }
  if (!newRoom.value.nodeId) {
    message.error($t("rooms.node-required"));
    return;
  }
  busy.value = true;
  try {
    const r = await api.frpRoomCreate(
      newRoom.value.name.trim(),
      newRoom.value.nodeId,
      newRoom.value.localPort,
      newRoom.value.password || undefined,
    );
    createShow.value = false;
    // 审核中的房间别人搜不到 —— 如实说，别让人以为已经能联机了
    if (r.review_status === "pending") {
      message.warning($t("rooms.pending"));
    } else {
      message.success($t("rooms.created"));
    }
    await refresh();
  } catch (e) {
    message.error(String(e));
  } finally {
    busy.value = false;
  }
}

/**
 * 房主「启动房间」/ 其它人「加入并连接」 —— 两件事不同，但都落到「起一条 frpc」上。
 * 访问端要**把自己该连的地址**告诉用户，否则他不知道游戏里填什么。
 */
async function enter(r: Room) {
  busy.value = true;
  try {
    if (!r.isHost) {
      await api.frpRoomJoin(r.id);
    }
    const res = r.isHost ? await api.frpRoomHostStart(r.id) : await api.frpRoomVisitorStart(r.id);
    if (!r.isHost && "connect" in res && res.connect) {
      notes.value[r.id] = $t("rooms.connect-hint", { p1: res.connect });
      message.success($t("rooms.connected", { p1: res.connect }));
    } else {
      notes.value[r.id] = $t("rooms.host-started");
      message.success($t("rooms.host-started"));
    }
    await refresh();
  } catch (e) {
    message.error(String(e));
  } finally {
    busy.value = false;
  }
}

/** 停掉 frpc，再退房（房主则是关房） */
async function exit(r: Room) {
  busy.value = true;
  try {
    await api.frpRoomStop(r.id).catch(() => {});
    if (r.isHost) {
      await api.frpRoomClose(r.id);
    } else {
      await api.frpRoomLeave(r.id);
    }
    delete notes.value[r.id];
    await refresh();
  } catch (e) {
    message.error(String(e));
  } finally {
    busy.value = false;
  }
}

onMounted(refresh);
</script>

<template>
  <div class="rooms">
    <div class="card glass">
      <h3>{{ $t("rooms.title") }}</h3>
      <p class="hint">{{ $t("rooms.desc") }}</p>
      <div class="btn-row">
        <button class="mini-btn" :disabled="busy" @click="refresh">{{ $t("frp.refresh") }}</button>
        <button class="mini-btn" :disabled="busy" @click="createShow = true">
          {{ $t("rooms.create") }}
        </button>
      </div>
    </div>

    <p v-if="busy && !rooms.length" class="hint">{{ $t("file-manager.loading") }}</p>
    <p v-else-if="!rooms.length" class="hint">{{ $t("rooms.empty") }}</p>

    <div v-for="r in rooms" :key="r.id" class="tun glass">
      <div class="tun-head">
        <span class="tun-name text-ellipsis">{{ r.name }}</span>
        <span class="badge" :class="r.status">{{ r.status }}</span>
      </div>
      <div class="tun-meta text-ellipsis">
        {{ r.gameName }} · {{ r.hostName }} · {{ r.playerCount }}/{{ r.maxPlayers }}
        <template v-if="r.hasPassword"> · {{ $t("rooms.locked") }}</template>
      </div>
      <p v-if="r.reviewStatus === 'pending'" class="hint warn">
        {{ $t("rooms.pending") }}
        <template v-if="r.reviewReason"> —— {{ r.reviewReason }}</template>
      </p>
      <p v-if="notes[r.id]" class="hint note">{{ notes[r.id] }}</p>
      <div class="btn-row">
        <button class="mini-btn" :disabled="busy" @click="enter(r)">
          {{ r.isHost ? $t("rooms.host-start") : $t("rooms.join") }}
        </button>
        <button class="mini-btn danger" :disabled="busy" @click="exit(r)">
          {{ r.isHost ? $t("rooms.close") : $t("rooms.leave") }}
        </button>
      </div>
    </div>

    <app-sheet v-model:show="createShow" :title="$t('rooms.create')">
      <div class="form">
        <!-- 用原生 `<input>` + 本页样式：`app-input` 在弹层里没能渲染出输入框
             （真机实测：标签在、框没有，什么都输不进去）。自己写一个输入框，
             外观可控，也不依赖全局组件在这里的行为。 -->
        <label class="row-label">
          {{ $t("rooms.name") }}
          <input v-model="newRoom.name" class="inp" :placeholder="$t('rooms.name-ph')" />
        </label>
        <label class="row-label">
          {{ $t("rooms.local-port") }}
          <input v-model.number="newRoom.localPort" class="inp" type="number" inputmode="numeric" />
        </label>
        <label class="row-label">
          {{ $t("rooms.password") }}
          <input v-model="newRoom.password" class="inp" :placeholder="$t('rooms.password-ph')" />
        </label>
        <div class="row-label">
          {{ $t("rooms.node") }}
          <div class="nodes">
            <button
              v-for="n in nodes"
              :key="n.id"
              class="node"
              :class="{ on: newRoom.nodeId === n.id, off: !n.online }"
              :disabled="!n.online"
              @click="newRoom.nodeId = n.id"
            >
              {{ n.name }}{{ n.online ? "" : `（${$t("rooms.node-offline")}）` }}
            </button>
          </div>
        </div>
        <div class="btn-row">
          <button class="mini-btn" :disabled="busy" @click="create">{{ $t("common.confirm") }}</button>
          <button class="mini-btn" @click="createShow = false">{{ $t("common.cancel") }}</button>
        </div>
      </div>
    </app-sheet>
  </div>
</template>

<style scoped>
.rooms {
  padding: 8px 16px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.card {
  padding: 14px;
  border-radius: 14px;
}
.card h3 {
  margin: 0 0 8px;
  font-size: 14px;
}
.hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-3);
}
.hint.warn {
  color: #e5a13b;
}
.hint.note {
  color: var(--accent);
}
.tun {
  padding: 12px 14px;
  border-radius: 14px;
}
.tun-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tun-name {
  font-size: 14px;
  font-weight: 600;
  min-width: 0;
}
.badge {
  margin-left: auto;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  color: var(--text-3);
}
.badge.waiting {
  color: var(--accent);
  border-color: var(--accent);
}
.btn-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.mini-btn {
  min-height: 34px;
  padding: 6px 14px;
  border-radius: 17px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-1);
  font-family: inherit;
  font-size: 13px;
}
.mini-btn.danger {
  color: #e5534b;
  border-color: #e5534b;
}
.mini-btn:disabled {
  opacity: 0.5;
}
.form {
  padding: 4px 2px 10px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.row-label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: var(--text-2);
}
/* 本页自己的输入框：文字色/背景都跟着主题变量走，深色浅色两种主题下都看得见 */
.inp {
  width: 100%;
  min-height: 40px;
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-1);
  font-family: inherit;
  font-size: 14px;
  box-sizing: border-box;
}
.inp::placeholder {
  color: var(--text-3);
}
.inp:focus {
  outline: none;
  border-color: var(--accent);
}
.nodes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.node {
  min-height: 32px;
  padding: 6px 12px;
  border-radius: 16px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-1);
  font-family: inherit;
  font-size: 12px;
}
.node.on {
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
.node.off {
  opacity: 0.5;
}
</style>
