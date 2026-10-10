#!/usr/bin/env python3
# =============================================================================
# 打补丁：将 fat-jar 内 ShardingSphere 分片配置中的数据库地址
# 127.0.0.1:3306 -> mysql:3306（容器网络内以 compose 服务名访问 MySQL）。
# 仅修改副本（staged jar），仓库原始 target jar 与源码不受影响。
# 用法：patch-jar.py <src.jar> <dst.jar>
# =============================================================================
import re
import sys
import zipfile

PATTERN = re.compile(rb"127\.0\.0\.1:3306")


def main(src: str, dst: str) -> None:
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if "shardingsphere" in item.filename and item.filename.endswith(".yaml"):
                new_data, count = PATTERN.subn(b"mysql:3306", data)
                if count:
                    print(f"  [patch] {item.filename}: {count} x 127.0.0.1:3306 -> mysql:3306")
                data = new_data
            zout.writestr(item, data)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
