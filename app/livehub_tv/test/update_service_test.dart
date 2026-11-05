import 'package:flutter_test/flutter_test.dart';
import 'package:livehub_tv/services/update_service.dart';

AppUpdateAsset asset(String name, {int size = 1024}) {
  return AppUpdateAsset(
    name: name,
    size: size,
    downloadUrl: "https://example.com/$name",
  );
}

void main() {
  group('语义化版本比较', () {
    test('补零后按段比较', () {
      expect(UpdateService.compareVersion("v1.0.0", "1.0.0"), 0);
      expect(UpdateService.compareVersion("1.0.1", "1.0.0"), 1);
      expect(UpdateService.compareVersion("1.0", "1.0.0"), 0);
      expect(UpdateService.compareVersion("1.10.0", "1.9.9"), 1);
    });

    test('预发布版低于正式版', () {
      expect(UpdateService.compareVersion("1.0.0-beta.1", "1.0.0"), -1);
      expect(UpdateService.compareVersion("1.0.0", "1.0.0-rc.1"), 1);
    });

    test('构建号只在主版本相同时参与比较', () {
      expect(UpdateService.compareVersion("1.0.0+2", "1.0.0+1"), 1);
      expect(UpdateService.compareVersion("1.0.1+0", "1.0.0+9"), 1);
    });
  });

  group('远端是否更新', () {
    test('同版本不提示，远端更高才提示', () {
      expect(
        UpdateService.isRemoteNewer(
          localVersion: "1.0.0",
          localBuildNumber: "1",
          remoteTag: "v1.0.0",
        ),
        isFalse,
      );
      expect(
        UpdateService.isRemoteNewer(
          localVersion: "1.0.0",
          localBuildNumber: "1",
          remoteTag: "v1.0.1",
        ),
        isTrue,
      );
    });
  });

  group('电视端 APK 资产选择', () {
    // 线上 v1.0.0 的真实资产清单：手机包和电视包混在同一个 release 里
    final releaseAssets = [
      asset("LiveHub-v1.0.0-arm64-v8a.apk", size: 40925692),
      asset("LiveHub-v1.0.0-armeabi-v7a.apk", size: 38909916),
      asset("LiveHub-v1.0.0-x86_64.apk", size: 45879658),
      asset("LiveHubTV-v1.0.0-arm64-v8a.apk", size: 36605242),
      asset("LiveHubTV-v1.0.0-armeabi-v7a.apk", size: 33966874),
    ];

    test('只挑电视包，绝不选到手机包', () {
      final picked = UpdateService.pickApkAsset(releaseAssets);
      expect(picked?.name, "LiveHubTV-v1.0.0-arm64-v8a.apk");
      expect(picked?.size, 36605242);
    });

    test('没有 arm64 时退到电视端 universal / armeabi-v7a', () {
      expect(
        UpdateService.pickApkAsset([
          asset("LiveHubTV-v1.0.0-universal.apk", size: 50),
          asset("LiveHubTV-v1.0.0-armeabi-v7a.apk", size: 40),
        ])?.name,
        "LiveHubTV-v1.0.0-universal.apk",
      );
      expect(
        UpdateService.pickApkAsset([
          asset("LiveHubTV-v1.0.0-armeabi-v7a.apk", size: 40),
        ])?.name,
        "LiveHubTV-v1.0.0-armeabi-v7a.apk",
      );
    });

    test('只有手机包时返回 null（不能把手机包装到电视上）', () {
      expect(
        UpdateService.pickApkAsset([
          asset("LiveHub-v1.0.0-arm64-v8a.apk"),
          asset("LiveHub-v1.0.0-universal.apk"),
        ]),
        isNull,
      );
    });

    test('isTvAsset 只认文件名里的 TV', () {
      expect(UpdateService.isTvAsset("LiveHubTV-v1.0.0-arm64-v8a.apk"), isTrue);
      expect(UpdateService.isTvAsset("livehubtv-v1.0.0.apk"), isTrue);
      expect(UpdateService.isTvAsset("LiveHub-v1.0.0-arm64-v8a.apk"), isFalse);
    });
  });

  group('发布页兜底：从 302 Location 解析 tag', () {
    test('正常 tag 页', () {
      expect(
        UpdateService.parseTagFromRedirect(
          "https://github.com/xiaopenyoua/LiveHub/releases/tag/v1.0.0",
        ),
        "v1.0.0",
      );
    });

    test('带查询参数或末尾斜杠', () {
      expect(
        UpdateService.parseTagFromRedirect(
          "https://github.com/xiaopenyoua/LiveHub/releases/tag/v1.0.1?foo=bar",
        ),
        "v1.0.1",
      );
      expect(
        UpdateService.parseTagFromRedirect(
          "https://github.com/xiaopenyoua/LiveHub/releases/tag/v1.0.1/",
        ),
        "v1.0.1",
      );
    });

    test('不是 tag 页时返回 null', () {
      expect(UpdateService.parseTagFromRedirect(null), isNull);
      expect(UpdateService.parseTagFromRedirect(""), isNull);
      expect(
        UpdateService.parseTagFromRedirect(
          "https://github.com/xiaopenyoua/LiveHub/releases",
        ),
        isNull,
      );
    });
  });

  group('GitHub release 解析', () {
    test('解析 tag_name / body / 资产，并选中电视包', () {
      final release = AppUpdateRelease.fromJson({
        "tag_name": "v1.0.0",
        "body": "首个正式版本",
        "published_at": "2026-11-05T08:00:00Z",
        "html_url": "https://github.com/xiaopenyoua/LiveHub/releases/tag/v1.0.0",
        "assets": [
          {
            "name": "LiveHub-v1.0.0-arm64-v8a.apk",
            "size": 40925692,
            "browser_download_url":
                "https://github.com/xiaopenyoua/LiveHub/releases/download/v1.0.0/LiveHub-v1.0.0-arm64-v8a.apk",
          },
          {
            "name": "LiveHubTV-v1.0.0-arm64-v8a.apk",
            "size": 36605242,
            "browser_download_url":
                "https://github.com/xiaopenyoua/LiveHub/releases/download/v1.0.0/LiveHubTV-v1.0.0-arm64-v8a.apk",
          },
        ],
      });

      expect(release.tagName, "v1.0.0");
      expect(release.version, "1.0.0");
      expect(release.notes, "首个正式版本");
      expect(release.assets.length, 2);
      expect(release.apk?.name, "LiveHubTV-v1.0.0-arm64-v8a.apk");
      expect(release.apk?.sizeText, "34.9 MB");
    });

    test('没有资产时不崩溃', () {
      final release = AppUpdateRelease.fromJson({
        "tag_name": "v1.0.1",
        "body": null,
        "assets": null,
      });
      expect(release.version, "1.0.1");
      expect(release.notes, "");
      expect(release.apk, isNull);
    });
  });
}
