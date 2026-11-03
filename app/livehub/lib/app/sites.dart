import 'package:livehub/app/constant.dart';
import 'package:livehub/app/controller/app_settings_controller.dart';
import 'package:livehub_core/livehub_core.dart';

class Sites {
  static final Map<String, Site> allSites = {
    Constant.kBiliBili: Site(
      id: Constant.kBiliBili,
      logo: "assets/images/bilibili_2.png",
      name: "哔哩哔哩",
      liveSite: BiliBiliSite(),
    ),
    Constant.kDouyu: Site(
      id: Constant.kDouyu,
      logo: "assets/images/douyu.png",
      name: "斗鱼直播",
      liveSite: DouyuSite(),
    ),
    Constant.kHuya: Site(
      id: Constant.kHuya,
      logo: "assets/images/huya.png",
      name: "虎牙直播",
      liveSite: HuyaSite(),
    ),
    Constant.kDouyin: Site(
      id: Constant.kDouyin,
      logo: "assets/images/douyin.png",
      name: "抖音直播",
      liveSite: DouyinSite(),
    ),
  };

  static List<Site> get supportSites {
    return AppSettingsController.instance.siteSort
        .map((key) => allSites[key]!)
        .toList();
  }
}

class Site {
  final String id;
  final String name;
  final String logo;
  final LiveSite liveSite;
  Site({
    required this.id,
    required this.liveSite,
    required this.logo,
    required this.name,
  });

  /// 设计规范 §2.2：平台用两个汉字表示，不加底、不用品牌色。
  String get shortName {
    switch (id) {
      case Constant.kBiliBili:
        return "哔哩";
      case Constant.kDouyu:
        return "斗鱼";
      case Constant.kHuya:
        return "虎牙";
      case Constant.kDouyin:
        return "抖音";
      default:
        return name;
    }
  }
}
