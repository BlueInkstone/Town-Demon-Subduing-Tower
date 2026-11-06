#!/usr/bin/env python3
"""把本地 m2 中已存在的 springdoc / swagger / webjars artifact 标记为 local 安装，
使其在离线（-o）构建中可被解析（绕过 _remote.repositories 的仓库 ID 不匹配问题）。"""
import os
import pathlib

M2 = pathlib.Path(os.path.expanduser("~/.m2/repository"))

GROUPS = {
    "org/springdoc": "org.springdoc",
    "io/swagger/core/v3": "io.swagger.core.v3",
    "org/webjars": "org.webjars",
    "com/fasterxml/jackson/dataformat": "com.fasterxml.jackson.dataformat",
    "com/fasterxml/jackson/core": "com.fasterxml.jackson.core",
    "com/fasterxml/jackson/datatype": "com.fasterxml.jackson.datatype",
    "com/fasterxml/jackson/module": "com.fasterxml.jackson.module",
    "org/apache/commons": "org.apache.commons",
    "org/yaml": "org.yaml",
}

MARKER = ("#NOTE# This is a Maven Resolver marker file, its content is used to "
          "track locally installed artifacts.\n")

marked = 0
for rel, gid in GROUPS.items():
    base = M2 / rel
    if not base.is_dir():
        continue
    for art_dir in base.iterdir():
        if not art_dir.is_dir():
            continue
        art = art_dir.name
        for ver_dir in art_dir.iterdir():
            if not ver_dir.is_dir():
                continue
            ver = ver_dir.name
            jar = ver_dir / f"{art}-{ver}.jar"
            pom = ver_dir / f"{art}-{ver}.pom"
            if not jar.exists() and not pom.exists():
                continue
            lines = [MARKER]
            if jar.exists():
                lines.append(f"{gid}:{art}:jar:{ver}=local\n")
            if pom.exists():
                lines.append(f"{gid}:{art}:pom:{ver}=local\n")
            (ver_dir / "_remote.repositories").write_text("".join(lines), encoding="utf-8")
            marked += 1

print(f"marked {marked} artifact dirs")
