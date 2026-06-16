#!/usr/bin/env bash
# uninstall.sh — 清理 OpenSpec 规范体系（Kiro）在本项目的接入
#
# 删除：.kiro/steering/00-openspec.md、本标准安装的 .kiro/hooks、openspec/specs/engineering-standards、
#       openspec/changes/_TEMPLATE、openspec/README.md
# 保留：你填写的 openspec/project.md、你自己的 openspec/changes/<你的变更> 与 archive、docs/DEV_CHANGELOG.md
#       （--purge 连 openspec/project.md 也删）
#
# 用法（项目根目录）：bash scripts/uninstall.sh [--yes] [--purge]

set -uo pipefail
ASSUME_YES=false; PURGE=false
for a in "$@"; do
  [ "$a" = "--yes" ] || [ "$a" = "-y" ] && ASSUME_YES=true
  [ "$a" = "--purge" ] && PURGE=true
done

echo "=== 清理 OpenSpec 规范体系（Kiro）==="
echo "项目：$(pwd)"
if [ "$ASSUME_YES" = false ]; then
  read -r -p "确认清理？[y/N] " ans; [[ "$ans" =~ ^[Yy]$ ]] || { echo "已取消"; exit 0; }
fi

rm -f .kiro/steering/00-openspec.md .kiro/steering/10-project-context.md .kiro/steering/20-workflow.md
rm -f .kiro/hooks/*.kiro.hook .kiro/hooks/post-check.sh
rm -rf openspec/specs/engineering-standards openspec/changes/_TEMPLATE openspec/README.md
echo "  ✅ 删除 steering / hooks / 通用 specs / 变更模板"

if [ "$PURGE" = true ]; then
  rm -f openspec/project.md && echo "  ✅ (--purge) 删除 openspec/project.md"
else
  echo "  ⏭  保留 openspec/project.md 与你自己的 changes/（彻底清除 project.md 用 --purge）"
fi
rm -f scripts/uninstall.sh 2>/dev/null || true
echo "=== 完成 ==="
