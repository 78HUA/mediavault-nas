import {ChangeDetectionStrategy, Component, ElementRef, OnDestroy, OnInit, ViewChild} from '@angular/core';
import DPlayer from 'dplayer';
import {Base64} from 'js-base64';
import {ActivatedRoute, Router} from "@angular/router";
import {NzMessageService} from "ng-zorro-antd/message";
import {environment} from "../../../../../environments/environment";
import {Link} from "../../../../http/model/Link";
import {FileEntity} from "../../../../http/model/FileEntity";
import {TranscodeInfo} from "../../../../http/model/TranscodeInfo";
import {VideoService} from "../../../../service/video.service";

@Component({
    selector: 'app-play',
    templateUrl: './play.component.html',
    styleUrls: ['./play.component.scss'],
    changeDetection: ChangeDetectionStrategy.Eager,
    standalone: false
})
export class PlayComponent implements OnInit, OnDestroy {

  /**
   * 这些后缀浏览器基本能直接解码，默认播原文件 —— 不用等转码，拖动也更跟手。
   * 其余格式（mkv / avi / wmv / rmvb 等）浏览器通常放不了，产物就绪时直接播产物。
   * <p>
   * 注意这里只看容器后缀、看不出编码：后缀是 mp4 但内部是 H.265 的同样放不了，
   * 那种情况由播放器的 error 事件兜底，而不是靠这个白名单判断。
   */
  private static readonly BROWSER_FRIENDLY_SUFFIX = new Set(['mp4', 'm4v', 'webm', 'ogv']);

  /**
   * 轮询转码产物的间隔
   */
  private static readonly POLL_INTERVAL_MS = 2000;

  /**
   * 最多轮询多少次（约 10 分钟）后放弃等待，避免长视频转码时无限轮询
   */
  private static readonly MAX_POLL_TIMES = 300;

  @ViewChild('videoPlayerElement', {static: true})
  private videoPlayerElement: ElementRef;

  private dp: DPlayer;
  private pollTimer: ReturnType<typeof setInterval> = null;
  private pollTimes = 0;
  /**
   * 真实文件路径。路由参数与播放地址用的是 Base64 编码，而转码接口要的是真实路径
   */
  private location: string;
  /**
   * 路由参数里的 Base64 路径，用于拼原文件播放地址
   */
  private playPath: string;

  breadcrumb: Link[] = [new Link()];
  transcodeInfo: TranscodeInfo = null;
  /**
   * 当前播的是不是转码产物
   */
  playingHls = false;
  /**
   * 是否正在等待转码完成
   */
  transcoding = false;
  /**
   * 原文件播放失败（容器或编码浏览器不支持）
   */
  playbackFailed = false;

  constructor(private route: ActivatedRoute,
              private videoService: VideoService,
              private message: NzMessageService,
              private router: Router) {
  }

  ngOnInit(): void {
    this.route.params.subscribe(path => {
      this.playPath = path['path'];
      this.location = Base64.decode(this.playPath);
      this.resetState();
      this.videoService.links(this.playPath).subscribe(data => this.breadcrumb = data);
      this.loadInfo(true);
    });
  }

  ngOnDestroy(): void {
    this.stopPolling();
    this.destroyPlayer();
  }

  /**
   * 播放原文件
   */
  playRaw(): void {
    if (!this.playingHls) {
      return;
    }
    this.startPlayback(false);
  }

  /**
   * 播放转码产物
   */
  playHls(): void {
    if (this.playingHls) {
      return;
    }
    if (!this.hasHls()) {
      this.message.info('转码产物还没就绪，请先点「转码后播放」');
      return;
    }
    this.startPlayback(true);
  }

  /**
   * 提交转码任务，完成后自动切换到转码版本
   */
  requestTranscode(): void {
    if (this.transcoding) {
      return;
    }
    this.transcoding = true;
    this.videoService.transcode(this.location).subscribe({
      next: result => {
        if (result.result === 'ALREADY_DONE') {
          // 产物其实已经在了（比如刚转完），直接切过去
          this.transcoding = false;
          this.message.success('转码产物已存在，正在切换');
          this.loadInfo(false);
          return;
        }
        this.message.info(result.accepted
            ? '已提交转码任务，完成后会自动切换'
            : '该文件已在转码队列中，完成后会自动切换');
        this.startPolling();
      },
      error: () => {
        // 具体原因（例如队列已满返回 429）已由响应拦截器统一提示
        this.transcoding = false;
      }
    });
  }

  hasHls(): boolean {
    return !!this.transcodeInfo && this.transcodeInfo.hlsReady;
  }

  go(item: FileEntity): void {
    const path = item.location;
    if (!item.file) {
      this.router.navigateByUrl(`/video/list/${path}`).catch(console.error);
    } else if (item.canPlay) {
      this.router.navigateByUrl(`/video/play/${path}`).catch(console.error);
    }
  }

  goFolder(path, last = false): void {
    if (last) {
      return;
    }
    this.router.navigateByUrl(`/video/list/${path}`).catch(console.error);
  }

  /**
   * 查询转码产物状态
   *
   * @param initial 是否为进入页面时的首次查询。首次只用来决定「先播哪个」；
   *                之后的查询是在等转码完成，就绪后自动切换
   */
  private loadInfo(initial: boolean): void {
    this.videoService.transcodeInfo(this.location).subscribe(info => {
      this.transcodeInfo = info;
      if (initial) {
        this.startPlayback(this.preferHls());
        return;
      }
      if (info.hlsReady) {
        this.stopPolling();
        this.transcoding = false;
        this.message.success('转码完成，已切换到转码版本');
        this.startPlayback(true);
        return;
      }
      if (++this.pollTimes >= PlayComponent.MAX_POLL_TIMES) {
        this.stopPolling();
        this.transcoding = false;
        this.message.warning('等待转码超时，已停止轮询；稍后可手动点「转码版本」');
      }
    });
  }

  /**
   * 是否优先播转码产物：浏览器放不了的格式，且产物已就绪
   */
  private preferHls(): boolean {
    return !this.isBrowserFriendly() && this.hasHls();
  }

  private isBrowserFriendly(): boolean {
    const dot = this.location.lastIndexOf('.');
    if (dot < 0) {
      return false;
    }
    return PlayComponent.BROWSER_FRIENDLY_SUFFIX.has(this.location.substring(dot + 1).toLowerCase());
  }

  private startPlayback(useHls: boolean): void {
    const url = useHls
        ? `${environment.backEndUrl}${this.transcodeInfo.m3u8Url}`
        : `${environment.backEndUrl}/video/${this.playPath}`;

    // 切换源时记住播放位置，否则转码完成后还要重新拖回去
    const currentTime = this.dp ? this.dp.video.currentTime : 0;
    this.destroyPlayer();

    this.playbackFailed = false;
    this.playingHls = useHls;
    this.dp = new DPlayer({
      container: this.videoPlayerElement.nativeElement,
      video: useHls ? this.hlsVideo(url) : {url},
      autoplay: true
    });
    this.dp.on('error', () => this.onPlaybackError());
    if (currentTime > 0) {
      this.dp.seek(currentTime);
    }
  }

  /**
   * 用本地打包的 hls.js 播放 m3u8
   * <p>
   * 两个刻意的选择：
   * ① 不用 dplayer 内置的 {@code type:'hls'} —— 那个会在运行时从 CDN 动态加载 hls.js，
   * 而 NAS 常常是内网离线环境，CDN 拉不到就是一片黑；
   * ② 用动态 import 而不是顶层 import —— hls.js 压缩后约 400 KB，而列表页、音乐页
   * 根本用不到它，打进主包会让所有人先下载再说话。
   */
  private hlsVideo(url: string): any {
    return {
      url,
      type: 'customHls',
      customType: {
        customHls: async (video: HTMLVideoElement) => {
          const {default: Hls} = await import('hls.js');
          const hls = new Hls();
          hls.loadSource(video.src);
          hls.attachMedia(video);
        }
      }
    };
  }

  private onPlaybackError(): void {
    this.playbackFailed = true;
    if (!this.playingHls) {
      this.message.warning('浏览器无法直接播放这个文件，请点「转码后播放」');
    }
  }

  private startPolling(): void {
    this.stopPolling();
    this.pollTimes = 0;
    this.pollTimer = setInterval(() => this.loadInfo(false), PlayComponent.POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.pollTimer !== null) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  private destroyPlayer(): void {
    if (this.dp) {
      this.dp.destroy();
      this.dp = null;
    }
  }

  private resetState(): void {
    this.stopPolling();
    this.destroyPlayer();
    this.transcodeInfo = null;
    this.playingHls = false;
    this.transcoding = false;
    this.playbackFailed = false;
    this.pollTimes = 0;
  }
}
