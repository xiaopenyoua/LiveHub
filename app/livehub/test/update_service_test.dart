import 'package:flutter_test/flutter_test.dart';
import 'package:livehub/services/update_service.dart';

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
      expect(UpdateService.compareVersion("2.0.0", "10.0.0"), -1);
    });

    test('预发布版低于正式版，预发布之间按标识符比较', () {
      expect(UpdateService.compareVersion("1.0.0-beta.1", "1.0.0"), -1);
      expect(UpdateService.compareVersion("1.0.0", "1.0.0-rc.1"), 1);
      expect(UpdateService.compareVersion("1.0.0-beta.2", "1.0.0-beta.10"), -1);
      expect(UpdateService.compareVersion("1.0.0-beta", "1.0.0-beta.1"), -1);
    });

    test('构建号只在主版本相同时参与比较', () {
      expect(UpdateService.compareVersion("1.0.0+2", "1.0.0+1"), 1);
      expect(UpdateService.compareVersion("1.0.0+1", "1.0.0"), 1);
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
      expect(
        UpdateService.isRemoteNewer(
          localVersion: "1.2.0",
          localBuildNumber: "7",
          remoteTag: "v1.1.9",
        ),
        isFalse,
      );
    });

    test('tag 里带构建号时可以继续比较 build', () {
      expect(
        UpdateService.isRemoteNewer(
          localVersion: "1.0.0",
          localBuildNumber: "1",
          remoteTag: "v1.0.0+2",
        ),
        isTrue,
      );
      expect(
        UpdateService.isRemoteNewer(
          localVersion: "1.0.0",
          localBuildNumber: "3",
          remoteTag: "v1.0.0+2",
        ),
        isFalse,
      );
    });
  });

  group('APK 资产选择', () {
    test('arm64 优先于 universal 与 armeabi-v7a', () {
      final picked = UpdateService.pickApkAsset([
        asset("LiveHub-v1.0.1-armeabi-v7a.apk", size: 30),
        asset("LiveHub-v1.0.1-universal.apk", size: 60),
        asset("LiveHub-v1.0.1-arm64-v8a.apk", size: 40),
        asset("LiveHub-v1.0.1.txt", size: 1),
      ]);
      expect(picked?.name, "LiveHub-v1.0.1-arm64-v8a.apk");
    });

    test('没有 arm64 时退到 universal', () {
      final picked = UpdateService.pickApkAsset([
        asset("LiveHub-v1.0.1-armeabi-v7a.apk"),
        asset("LiveHub-v1.0.1-universal.apk"),
      ]);
      expect(picked?.name, "LiveHub-v1.0.1-universal.apk");
    });

    test('排除 TV 端安装包，找不到时返回 null', () {
      expect(
        UpdateService.pickApkAsset([
          asset("LiveHubTV-v1.0.1-arm64-v8a.apk"),
          asset("LiveHub-v1.0.1-arm64-v8a.apk"),
        ])?.name,
        "LiveHub-v1.0.1-arm64-v8a.apk",
      );
      expect(
        UpdateService.pickApkAsset([asset("LiveHubTV-v1.0.1.apk")]),
        isNull,
      );
      expect(UpdateService.pickApkAsset([asset("release-notes.txt")]), isNull);
    });
  });

  group('GitHub release 解析', () {
    test('解析 tag_name / body / 资产并选中 APK', () {
      final release = AppUpdateRelease.fromJson({
        "tag_name": "v1.2.3",
        "body": "修复了一些问题\n新增在线更新",
        "published_at": "2026-11-05T08:00:00Z",
        "html_url": "https://github.com/xiaopenyoua/LiveHub/releases/tag/v1.2.3",
        "assets": [
          {
            "name": "LiveHub-v1.2.3-arm64-v8a.apk",
            "size": 45678901,
            "browser_download_url":
                "https://github.com/xiaopenyoua/LiveHub/releases/download/v1.2.3/LiveHub-v1.2.3-arm64-v8a.apk",
          },
          {
            "name": "LiveHub-v1.2.3-universal.apk",
            "size": 78901234,
            "browser_download_url":
                "https://github.com/xiaopenyoua/LiveHub/releases/download/v1.2.3/LiveHub-v1.2.3-universal.apk",
          },
        ],
      });

      expect(release.tagName, "v1.2.3");
      expect(release.version, "1.2.3");
      expect(release.notes, contains("在线更新"));
      expect(release.publishedAt, isNotNull);
      expect(release.assets.length, 2);
      expect(release.apk?.name, "LiveHub-v1.2.3-arm64-v8a.apk");
      expect(release.apk?.sizeText, "43.6 MB");
    });

    test('没有资产时不崩溃', () {
      final release = AppUpdateRelease.fromJson({
        "tag_name": "v1.0.1",
        "body": null,
        "assets": null,
      });
      expect(release.version, "1.0.1");
      expect(release.notes, "");
      expect(release.assets, isEmpty);
      expect(release.apk, isNull);
    });
  });
}
