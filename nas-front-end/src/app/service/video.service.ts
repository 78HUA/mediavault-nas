import {Injectable} from '@angular/core';
import {Observable} from "rxjs";
import {FileEntity} from "../http/model/FileEntity";
import { HttpClient } from "@angular/common/http";
import {environment} from "../../environments/environment";
import {Link} from "../http/model/Link";
import {TranscodeInfo} from "../http/model/TranscodeInfo";
import {TranscodeResult} from "../http/model/TranscodeResult";

@Injectable({
  providedIn: 'root'
})
export class VideoService {

  constructor(private http: HttpClient) {
  }

  location(path: string = undefined): Observable<FileEntity[]> {
    return this.http.get<FileEntity[]>(`${environment.backEndUrl}/location?path=${path ? path : ''}`)
  }

  links(path: string = undefined): Observable<Link[]> {
    return this.http.get<Link[]>(`${environment.backEndUrl}/links?path=${path ? path : ''}`)
  }

  /**
   * 查询某个视频的转码产物状态
   * <p>
   * 只读接口，不会触发转码，可以放心轮询。
   *
   * @param location 视频文件路径（真实路径，不是列表接口给的那个 Base64）
   */
  transcodeInfo(location: string): Observable<TranscodeInfo> {
    return this.http.get<TranscodeInfo>(`${environment.backEndUrl}/transcode/info?location=${encodeURIComponent(location)}`)
  }

  /**
   * 提交转码任务
   * <p>
   * 异步接口：立即返回，不等待转码完成，产物要之后查询或轮询。
   *
   * @param location 视频文件路径（真实路径，不是列表接口给的那个 Base64）
   */
  transcode(location: string): Observable<TranscodeResult> {
    return this.http.post<TranscodeResult>(`${environment.backEndUrl}/transcode?location=${encodeURIComponent(location)}`, null)
  }
}
