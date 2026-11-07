#!/data/data/com.termux/files/usr/bin/bash
set -e
pkg update -y
pkg install -y python lua53
python -m pip install --upgrade pip
python -m pip install rich
mkdir -p "$HOME/DAFAN_RE_PRO/EXES"   "$HOME/DAFAN_RE_PRO/LUA_TOOL/ORIGINAL LUA"   "$HOME/DAFAN_RE_PRO/LUA_TOOL/SOURCE LUA"   "$HOME/DAFAN_RE_PRO/LUA_TOOL/EDITED LUA"   "$HOME/DAFAN_RE_PRO/LUA_TOOL/COMPILED LUA"
echo "Setup complete. Put a compatible luadec executable in ~/DAFAN_RE_PRO/EXES/luadec"
