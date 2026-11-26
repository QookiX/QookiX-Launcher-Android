import { execFileSync } from "node:child_process";
import { ANDROID_VERSION_NAME, CHANNEL_MAX, androidVersionCode } from "./android-version.mjs";

function verifyReleaseApks(tag, aapt, apks) {
  const versionName = tag?.startsWith("v") ? tag.slice(1) : "";
  const version = ANDROID_VERSION_NAME.exec(versionName);
  if (!version || !aapt || apks.length !== 2 || apks[0] === apks[1]) {
    throw new Error("用法: node scripts/verify-release-apks.mjs <vX.Y.Z[-betaN]> <aapt> <arm64.apk> <x86_64.apk>");
  }

  const [, majorText, minorText, patchText, channel, preText] = version;
  const [major, minor, patch] = [majorText, minorText, patchText].map(Number);
  const pre = channel ? Number(preText) : 0;
  const versionCode = androidVersionCode(major, minor, patch, channel, pre);
  if (minor > 99 || patch > 99 || (channel && (pre < 1 || pre > CHANNEL_MAX[channel])) ||
      !Number.isInteger(versionCode) || versionCode < 1 || versionCode > 2_147_483_647) {
    throw new Error(`${tag} 无法编码为有效的 Android versionCode`);
  }

  // 两个 APK 都必须匹配同一预期值；也就保证不同 ABI 的版本一致。
  for (const apk of apks) {
    const badging = execFileSync(aapt, ["dump", "badging", apk], { encoding: "utf8" });
    const pkg = badging.split(/\r?\n/).find((line) => line.startsWith("package: ")) ?? "";
    const actualName = /\bversionName='([^']*)'/.exec(pkg)?.[1];
    const actualCode = /\bversionCode='([1-9]\d*)'/.exec(pkg)?.[1];
    if (actualName !== versionName || actualCode !== String(versionCode)) {
      throw new Error(`${apk}: versionName=${actualName ?? "缺失"}, versionCode=${actualCode ?? "缺失或无效"}；${tag} 要求 versionName=${versionName}, versionCode=${versionCode}`);
    }
    console.log(`${apk}: versionName=${versionName}, versionCode=${versionCode}`);
  }
}

const [tag, aapt, ...apks] = process.argv.slice(2);
try {
  verifyReleaseApks(tag, aapt, apks);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
