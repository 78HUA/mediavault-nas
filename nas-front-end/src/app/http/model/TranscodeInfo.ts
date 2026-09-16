/**
 * 某个视频的转码产物状态
 */
export class TranscodeInfo {
  /**
   * 视频文件路径
   */
  location: string;
  /**
   * 路径 MD5，转码产物目录名
   */
  locationMd5: string;
  /**
   * 转码产物是否已就绪
   */
  hlsReady: boolean;
  /**
   * 转码产物地址（m3u8）
   */
  m3u8Url: string;
  /**
   * 是否正在转码
   */
  transcoding: boolean;
}
