import 'package:extended_image/extended_image.dart';
import 'package:flutter/material.dart';
import 'package:livehub_ui/livehub_ui.dart';

class NetImage extends StatelessWidget {
  final String picUrl;
  final double? width;
  final double? height;
  final BoxFit? fit;
  final double borderRadius;
  final int? cacheWidth;
  const NetImage(this.picUrl,
      {this.width,
      this.height,
      this.fit = BoxFit.cover,
      this.borderRadius = 0,
      this.cacheWidth,
      Key? key})
      : super(key: key);

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    var pic = picUrl;
    if (pic.startsWith("//")) {
      pic = 'https:$pic';
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(borderRadius),
      child: ExtendedImage.network(
        pic,
        fit: fit,
        height: height,
        width: width,
        cacheWidth: cacheWidth,
        shape: BoxShape.rectangle,
        borderRadius: BorderRadius.circular(borderRadius),
        loadStateChanged: (e) {
          if (e.extendedImageLoadState == LoadState.loading) {
            // §十：加载中是 --panel 纯色，不闪光、不骨架跳动。
            return ColoredBox(color: t.panel);
          }
          if (e.extendedImageLoadState == LoadState.failed) {
            return ColoredBox(
              color: t.panel,
              child: Center(
                child: Icon(
                  Icons.broken_image_outlined,
                  color: t.faint,
                  size: 24 * t.unit,
                ),
              ),
            );
          }
          return null;
        },
      ),
    );
  }
}
