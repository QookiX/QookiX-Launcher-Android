package dalvik.annotation.optimization;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 编译期桩：{@code dalvik.annotation.optimization.CriticalNative} 是 Android 平台的 @hide 注解
 * （Pojav 的 `org.lwjgl.glfw.CallbackBridge` 用它标记输入相关的 native 方法，以走
 * "critical native" 快路径，省掉 JNIEnv 参数）。
 *
 * 运行期真正生效的是系统里那一份（boot classpath 优先，本类被遮蔽），
 * 这里只为了让 `net.kdt.pojavlaunch.CriticalNativeTest` / `CallbackBridge` 能编译通过。
 * 如果设备不支持 critical native，libpojavexec 的 `tryCriticalNative()` 会探测失败并
 * 自动注册普通 JNI 版本，功能不受影响。
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface CriticalNative {
}
