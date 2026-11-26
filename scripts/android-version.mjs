export const ANDROID_VERSION_NAME = /^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.?(\d+))?$/;
export const CHANNEL_MAX = { alpha: 19, beta: 50, rc: 29 };

const CHANNEL_BASE = { alpha: 1, beta: 20, rc: 70 };
const RELEASE_PRE = 99;

// AA BB CC DD: major, minor, patch, and release channel/sequence.
export function androidVersionCode(major, minor, patch, channel, pre) {
  const preCode = channel ? CHANNEL_BASE[channel] + (pre - 1) : RELEASE_PRE;
  return major * 1_000_000 + minor * 10_000 + patch * 100 + preCode;
}
