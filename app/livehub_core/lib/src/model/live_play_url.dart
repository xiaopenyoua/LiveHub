import 'dart:convert';

/// 一条播放线路的元信息。
///
/// 平台一次会同时签发 FLV 与 HLS，两者失败的姿势完全不同（FLV 断流是 EOF、
/// HLS 分片 404 是片段级错误），所以在换线的时候需要知道「这一条是什么」，
/// 而不是把地址当成一坨字符串。
class LivePlayLine {
  /// 完整播放地址
  final String url;

  /// 线路类型：`FLV` / `HLS·fMP4` / `HLS·TS`
  final String kind;

  /// 平台本次实际签发的清晰度（qn）
  final int quality;

  /// CDN host
  final String host;

  LivePlayLine({
    required this.url,
    required this.kind,
    this.quality = 0,
    this.host = "",
  });

  @override
  String toString() => "$kind(qn=$quality) $host";
}

class LivePlayUrl {
  /// 播放地址
  final List<String> urls;

  /// 请求头
  final Map<String, String>? headers;

  /// 与 [urls] 一一对应的线路元信息（可能为空，取决于平台实现）
  final List<LivePlayLine> lines;

  /// 平台实际签发的清晰度。请求原画却只给超清的房间里，用它纠正界面徽章。
  final int? actualQuality;

  LivePlayUrl({
    required this.urls,
    this.headers,
    this.lines = const [],
    this.actualQuality,
  });

  /// 第 [index] 条线路的类型标签，超出范围时返回空串。
  String kindOf(int index) {
    if (index < 0 || index >= lines.length) {
      return "";
    }
    return lines[index].kind;
  }

  @override
  String toString() {
    return json.encode({
      "urls": urls,
      "headers": headers.toString(),
      "actualQuality": actualQuality,
    });
  }
}
