# 由 pre-commit / pre-push 引入（`. "$(dirname "$0")/private-check.sh"`），不单独运行。
#
# 维护者的检查脚本放在不入库的私有目录里。按下面的次序找，找到哪一个就打印哪一个：
#   1. 环境变量 HEAVYSEAS_WIKI —— 显式给了却没有那个脚本就失败，不往下退；
#   2. 本工作区的 docs/；
#   3. 主工作区的 docs/ —— 在 git worktree 里提交时，本工作区没有 docs/；
#   4. 主工作区同级的 VibeCodingWiki/（协作者的布局）。
# 都找不到就失败。没有私有脚本的克隆，设 HEAVYSEAS_SKIP_PRIVATE_CHECKS=1 才跳过。
#
# 为什么找不到要失败：以前这里打印一句「普通克隆里属正常」就放行，
# 于是在工作树里提交时守卫一次都没跑，而输出与跑过了一样平静（2026-10-07 审查 G1）。

run_private_check() {
  name="$1"
  shift
  root="$(git rev-parse --show-toplevel)"
  main_root="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"

  if [ -n "${HEAVYSEAS_WIKI:-}" ]; then
    if [ ! -f "$HEAVYSEAS_WIKI/tools/$name" ]; then
      echo "❌ HEAVYSEAS_WIKI=$HEAVYSEAS_WIKI 下没有 tools/$name"
      return 1
    fi
    check="$HEAVYSEAS_WIKI/tools/$name"
  elif [ -f "$root/docs/tools/$name" ]; then
    check="$root/docs/tools/$name"
  elif [ -f "$main_root/docs/tools/$name" ]; then
    check="$main_root/docs/tools/$name"
  elif [ -f "$(dirname "$main_root")/VibeCodingWiki/tools/$name" ]; then
    check="$(dirname "$main_root")/VibeCodingWiki/tools/$name"
  elif [ "${HEAVYSEAS_SKIP_PRIVATE_CHECKS:-}" = "1" ]; then
    echo "提示：HEAVYSEAS_SKIP_PRIVATE_CHECKS=1，跳过维护者的本地检查（$name）。"
    return 0
  else
    echo "❌ 找不到维护者的本地检查脚本 $name。找过："
    echo "     $root/docs/tools/"
    echo "     $main_root/docs/tools/"
    echo "     $(dirname "$main_root")/VibeCodingWiki/tools/"
    echo "   没有私有脚本的克隆：HEAVYSEAS_SKIP_PRIVATE_CHECKS=1 git ..."
    return 1
  fi

  echo "本地检查：$check"
  "${BASH:-bash}" "$check" "$@"
}
