import 'package:livehub_core/livehub_core.dart';
import 'package:livehub_tv/app/app_focus_node.dart';

class Sites {
  static final Map<String, Site> allSites = {
    "bilibili": Site(
      id: "bilibili",
      logo: "assets/images/bilibili_2.png",
      name: "哔哩哔哩",
      liveSite: BiliBiliSite(),
      index: 0,
    ),
    "douyu": Site(
      id: "douyu",
      logo: "assets/images/douyu.png",
      name: "斗鱼直播",
      liveSite: DouyuSite(),
      index: 1,
    ),
    "huya": Site(
      id: "huya",
      logo: "assets/images/huya.png",
      name: "虎牙直播",
      liveSite: HuyaSite(),
      index: 2,
    ),
    "douyin": Site(
      id: "douyin",
      logo: "assets/images/douyin.png",
      name: "抖音直播",
      liveSite: DouyinSite(),
      index: 3,
    ),
  };

  static List<Site> get supportSites {
    return allSites.values.toList();
  }
}

class Site {
  final String id;
  final String name;
  final String logo;
  final LiveSite liveSite;
  final int index;
  AppFocusNode appFocusNode = AppFocusNode();

  /// 设计规范 §2.2：平台用两个汉字表示，不加底、不用品牌色。
  String get shortName {
    switch (id) {
      case "bilibili":
        return "哔哩";
      case "douyu":
        return "斗鱼";
      case "huya":
        return "虎牙";
      case "douyin":
        return "抖音";
      default:
        return name;
    }
  }

  Site({
    required this.id,
    required this.liveSite,
    required this.logo,
    required this.name,
    required this.index,
  });
}
