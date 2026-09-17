import { Component, OnInit, SecurityContext, ChangeDetectionStrategy } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from "@angular/forms";
import { DomSanitizer, SafeUrl } from "@angular/platform-browser";
import { MusicService } from "../../../../service/music.service";
import { NzMessageService } from "ng-zorro-antd/message";
import * as musicMetadata from "music-metadata-browser";
import { Router } from "@angular/router";

@Component({
  selector: 'app-add',
  templateUrl: './add.component.html',
  styleUrls: ['./add.component.scss'],
  changeDetection: ChangeDetectionStrategy.Eager,
  standalone: false
})
export class AddComponent implements OnInit {

  formGroup: UntypedFormGroup;

  type: number;

  musicUri: string | SafeUrl;

  coverUri: string;

  lyric: string = '';

  addLoading = false;

  private files: { [key: string]: File } = {};

  constructor(private musicService: MusicService,
    private fb: UntypedFormBuilder,
    private message: NzMessageService,
    private sanitizer: DomSanitizer,
    private router: Router) {
  }

  ngOnInit(): void {
    this.formGroup = this.fb.group({
      name: [null, [Validators.required]],
      singer: [null, [Validators.required]],
    });
  }

  submitForm() {
    if (!this.formGroup.valid) {
      this.message.error('请检查必填字段！');
      return;
    }
    const formData = new FormData();
    for (let key in this.files) {
      formData.append(key, this.files[key]);
    }
    for (let key in this.formGroup.value) {
      formData.append(key, this.formGroup.value[key]);
    }
    this.addLoading = true;
    this.musicService.addMusic(formData).subscribe(data => {
      this.addLoading = false;
      console.log(data);
      this.message.success('新增成功');
      this.router.navigateByUrl('/music/list').catch(console.error);
    }, error => {
      this.addLoading = false;
      console.error(error);
      const errorMsg = error.error?.msg ?? '出错啦';
      this.message.error(errorMsg);
    });
  }

  handleFile($event: any, formGroupName: string) {
    const file: File = $event.target.files[0];
    this.files[formGroupName] = file;
    switch (formGroupName) {
      case 'musicFile': {
        this.musicUri = this.sanitizer.bypassSecurityTrustUrl(URL.createObjectURL(file));
        this.type = this.coverMusicType(file.type);
        // 先用文件名兜底填歌名：大量音频（音效、下载来的曲子）根本没有内嵌标签，
        // 而歌名/歌手是必填项 —— 不兜底就提交不了，等于这类文件压根加不进来。
        // 歌手不兜底：编不出有意义的值，留空让用户自己填。
        this.formGroup.patchValue({ name: file.name.replace(/\.[^.]+$/, '') });
        musicMetadata.parseBlob(file).then(data => {
          // 只有文件确实带了标签才覆盖兜底值。
          // 注意不能写成无条件 patchValue：标签为空时会把 undefined 写进去，反而把兜底值抹掉。
          if (data.common.title) {
            this.formGroup.patchValue({ name: data.common.title });
          }
          if (data.common.artist) {
            this.formGroup.patchValue({ singer: data.common.artist });
          }
          const picture = data.common.picture;
          if (picture && picture[0]) {
            this.coverUri = this.sanitizer.sanitize(SecurityContext.URL, this.sanitizer.bypassSecurityTrustUrl(URL.createObjectURL(new Blob([new Uint8Array(picture[0].data)]))))
          }
        }).catch(() => {
          // 标签解析失败（格式异常、文件损坏）不应阻塞添加：保留文件名兜底值，让用户自己核对
        });
        break;
      }
      case 'lyricFile': {
        this.musicService.getLyricFromUrl(this.sanitizer.sanitize(SecurityContext.URL, this.sanitizer.bypassSecurityTrustUrl(URL.createObjectURL(file)))).subscribe(lyric => this.lyric = lyric);
        break;
      }
      case 'coverFile': {
        this.coverUri = this.sanitizer.sanitize(SecurityContext.URL, this.sanitizer.bypassSecurityTrustUrl(URL.createObjectURL(file)));
        break;
      }
    }
  }

  private coverMusicType(type: string): number {
    switch (type) {
      case "audio/flac":
        return 1;
      case "audio/mpeg":
        return 2;
      case "audio/wav":
        return 3;
      case "audio/aac":
        return 4;
    }
  }

}
