/**
 * 转码任务的提交结果
 */
export class TranscodeResult {
  /**
   * 视频文件路径
   */
  location: string;
  /**
   * 路径 MD5，转码产物目录名
   */
  locationMd5: string;
  /**
   * 提交结果：SUBMITTED / ALREADY_IN_PROGRESS / ALREADY_DONE / REJECTED
   */
  result: string;
  /**
   * 本次是否真的被受理（仅 SUBMITTED 为 true）
   */
  accepted: boolean;
  /**
   * 转码产物地址（m3u8）
   */
  m3u8Url: string;
}
