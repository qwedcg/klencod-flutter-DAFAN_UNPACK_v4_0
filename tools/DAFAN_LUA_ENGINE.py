#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DAFAN RE PRO Lua engine adapted from the user-provided nadeem.py.
Supports only the custom Lua 5.3 chunk layout implemented in that source.
"""
import argparse
import os, sys, subprocess, shutil, struct, re, tempfile
from pathlib import Path
from typing import List, Optional, Tuple
try:
    from rich.console import Console
    console = Console()
except Exception:
    class _Console:
        def print(self, *args, **kwargs): print(*args)
    console = _Console()
BASE_DIR = Path.home() / "DAFAN_RE_PRO"
LUA_BASE = BASE_DIR / "LUA_TOOL"
ORIGINAL_LUA_DIR = LUA_BASE / "ORIGINAL LUA"
SOURCE_LUA_DIR = LUA_BASE / "SOURCE LUA"
EDITED_LUA_DIR = LUA_BASE / "EDITED LUA"
COMPILED_LUA_DIR = LUA_BASE / "COMPILED LUA"
EXES_DIR = BASE_DIR / "EXES"

def ensure_dirs():
    for d in (ORIGINAL_LUA_DIR, SOURCE_LUA_DIR, EDITED_LUA_DIR, COMPILED_LUA_DIR, EXES_DIR):
        d.mkdir(parents=True, exist_ok=True)

_K = bytes.fromhex("112136474657a78d9d8490d8ab008c35261af7e45805b8b31507d02c1e8ff6c8")

OP_MOVE=17; OP_LOADK=18; OP_LOADBOOL=3; OP_LOADNIL=21; OP_GETUPVAL=22
OP_GETTABUP=23; OP_GETTABLE=24; OP_SETTABUP=8; OP_SETTABLE=27
OP_NEWTABLE=28; OP_SELF=29; OP_ADD=13; OP_SUB=14; OP_MUL=2; OP_DIV=5
OP_LEN=20; OP_CONCAT=16; OP_UNM=25; OP_NOT=26; OP_JMP=30; OP_EQ=31
OP_LT=32; OP_LE=33; OP_TEST=34; OP_TESTSET=35; OP_CALL=36; OP_TAILCALL=37
OP_RETURN=38; OP_FORLOOP=39; OP_FORPREP=40; OP_TFORCALL=41; OP_TFORLOOP=42
OP_SETLIST=43; OP_CLOSURE=44; OP_VARARG=45; OP_EXTRAARG=46

LUA_NIL=0; LUA_BOOL=1; LUA_NUM=3; LUA_FLOAT=19; LUA_STR=4; LUA_STRL=20

CUSTOM_TO_STD = {
    17:0, 18:1, 3:3, 21:4, 22:5, 23:6, 24:7, 8:8,
    27:10, 28:11, 29:12, 13:13, 14:14, 2:15, 5:18,
    20:28, 16:29, 25:25, 26:27, 30:30, 31:31, 32:32,
    33:33, 34:34, 35:35, 36:36, 37:37, 38:38, 39:39,
    40:40, 41:41, 42:42, 43:43, 44:44, 45:45, 46:46
}
STD_TO_CUSTOM = {v: k for k, v in CUSTOM_TO_STD.items()}

# ============================================================================
# DECOMPILATION LOGIC
# ============================================================================

def _xor(data, key):
    return bytes([data[i] ^ key[i % len(key)] for i in range(len(data))])

class Reader_new:
    def __init__(self, d, k): self.d=d; self.pos=0; self.k=k
    def read(self, n):
        v=bytes(self.d[self.pos:self.pos+n]); self.pos+=n; return v
    def byte(self):
        v=self.d[self.pos]; self.pos+=1; return v
    def i32(self):
        v=struct.unpack_from('<i',self.d,self.pos)[0]; self.pos+=4; return v
    def i64(self):
        v=struct.unpack_from('<q',self.d,self.pos)[0]; self.pos+=8; return v
    def dbl(self):
        v=struct.unpack_from('<d',self.d,self.pos)[0]; self.pos+=8; return v
    def string(self):
        sz=self.byte()
        if sz==0: return None
        if sz==0xFF:
            sz=struct.unpack_from('<Q',self.d,self.pos)[0]; self.pos+=8
        raw=bytes(self.d[self.pos:self.pos+sz-1]); self.pos+=sz-1
        b=_xor(raw,self.k)
        try: return b.decode('utf-8')
        except: return b.decode('latin-1',errors='replace')

class P:
    __slots__=('src','ld','ll','np','va','ms','ins','K','upvals','upvs','locs','subs')
    def __init__(self):
        self.src=None;self.ld=0;self.ll=0;self.np=0;self.va=0;self.ms=0
        self.ins=[];self.K=[];self.upvals=b'';self.upvs=[];self.locs=[];self.subs=[]

def parse(r):
    p=P()
    try:
        p.src=r.string()
        p.ld=r.i32(); p.ll=r.i32()
        p.np=r.byte(); p.va=r.byte(); p.ms=r.byte()
        n=r.i32()
        p.ins=[struct.unpack_from('<I',r.read(4))[0] for _ in range(n)]
        n=r.i32()
        for _ in range(n):
            t=r.byte()
            if t==LUA_NIL:   p.K.append(('nil',None))
            elif t==LUA_BOOL: b=r.byte(); p.K.append(('bool',bool(b)))
            elif t==LUA_NUM:
                raw=r.i64(); fv=struct.unpack('<d',struct.pack('<q',raw))[0]
                p.K.append(('float',fv))
            elif t==LUA_FLOAT:
                raw=r.dbl(); iv=struct.unpack('<q',struct.pack('<d',raw))[0]
                p.K.append(('int',iv))
            elif t in (LUA_STR,LUA_STRL): s=r.string(); p.K.append(('str',s))
            else: raise ValueError(f'Unknown const {t}')
        n=r.i32()
        p.upvals = r.read(n*2)
        n=r.i32()
        for _ in range(n): p.subs.append(parse(r))
        n=r.i32(); r.read(n); n=r.i32(); r.read(n*8); n=r.i32()
        for _ in range(n):
            nm=r.string(); sp=r.i32(); ep=r.i32()
            if nm: p.locs.append((nm,sp,ep))
        n=r.i32()
        for _ in range(n):
            s=r.string()
            if s: p.upvs.append(s)
        return p
    except Exception: return None

def load_proto(path):
    try:
        with open(path,'rb') as f: d=bytearray(f.read())
        if len(d)<34 or d[:4]!=b'\x1bLua' or d[4]!=0x53: return None
        r=Reader_new(d,_K); r.pos=34
        return parse(r)
    except: return None

class UniversalWriter:
    def __init__(self, mode):
        self.data = bytearray()
        self.mode = mode
        self.is_53 = "5.3" in mode
        self.is_32 = "_32" in mode
    def write(self, b): self.data.extend(b)
    def byte(self, v): self.data.append(v & 0xFF)
    def i32(self, v): self.data.extend(struct.pack('<i', v))
    def i64(self, v): self.data.extend(struct.pack('<q', v))
    def dbl(self, v): self.data.extend(struct.pack('<d', v))
    def string(self, s):
        if s is None:
            self.i32(0) if not self.is_53 else self.byte(0)
        else:
            b = s.encode('utf-8')
            sz = len(b) + 1
            if self.is_53:
                if sz < 0xFF:
                    self.byte(sz)
                else:
                    self.byte(0xFF)
                    fmt = '<I' if self.is_32 else '<Q'
                    self.data.extend(struct.pack(fmt, sz))
                self.write(b)
            else:
                fmt = '<I' if self.is_32 else '<Q'
                self.data.extend(struct.pack(fmt, sz))
                self.write(b)
    def write_proto(self, p):
        self.string(p.src)
        self.i32(p.ld)
        self.i32(p.ll)
        if not self.is_53: self.byte(len(p.upvals) // 2)
        self.byte(p.np)
        self.byte(1 if p.va else 0)
        self.byte(250)
        self.i32(len(p.ins))
        for ins in p.ins:
            op = ins & 0x3F
            std_op = CUSTOM_TO_STD.get(op, op)
            if std_op > 46: std_op = 0
            new_ins = (ins & ~0x3F) | std_op
            self.data.extend(struct.pack('<I', new_ins))
        self.i32(len(p.K))
        for typ, val in p.K:
            if typ == 'nil':
                self.byte(0)
            elif typ == 'bool':
                self.byte(1); self.byte(1 if val else 0)
            elif typ == 'float':
                self.byte(3); self.dbl(val)
            elif typ == 'int':
                if self.is_53:
                    self.byte(19)
                    if "int4" in self.mode: self.i32(int(val))
                    else: self.i64(int(val))
                else:
                    self.byte(3); self.dbl(float(val))
            elif typ == 'str':
                if self.is_53:
                    b = val.encode('utf-8')
                    if len(b) >= 40: self.byte(20)
                    else: self.byte(4)
                else:
                    self.byte(4)
                self.string(val)
        if self.is_53:
            n_ups = len(p.upvals) // 2
            self.i32(n_ups)
            self.write(p.upvals)
        self.i32(len(p.subs))
        for sub in p.subs: self.write_proto(sub)
        self.i32(len(p.ins))
        for _ in range(len(p.ins)): self.i32(1)
        self.i32(len(p.locs))
        for nm, sp, ep in p.locs:
            self.string(nm); self.i32(sp); self.i32(ep)
        n_ups = len(p.upvals) // 2
        self.i32(n_ups)
        for i in range(n_ups):
            name = p.upvs[i] if i < len(p.upvs) else f"upval_{i}"
            self.string(name)

def build_std_file(proto, mode, out_path):
    w = UniversalWriter(mode)
    if mode == "5.3_64":
        w.data.extend(b'\x1bLua\x53\x00\x19\x93\r\n\x1a\n\x04\x08\x04\x08\x08')
        w.data.extend(struct.pack('<q', 0x5678))
        w.data.extend(struct.pack('<d', 370.5))
        w.byte(len(proto.upvals) // 2)
    elif mode == "5.3_32":
        w.data.extend(b'\x1bLua\x53\x00\x19\x93\r\n\x1a\n\x04\x04\x04\x08\x08')
        w.data.extend(struct.pack('<q', 0x5678))
        w.data.extend(struct.pack('<d', 370.5))
        w.byte(len(proto.upvals) // 2)
    elif mode == "5.3_32_int4":
        w.data.extend(b'\x1bLua\x53\x00\x19\x93\r\n\x1a\n\x04\x04\x04\x04\x08')
        w.data.extend(struct.pack('<i', 0x5678))
        w.data.extend(struct.pack('<d', 370.5))
        w.byte(len(proto.upvals) // 2)
    elif mode == "5.1_32":
        w.data.extend(b'\x1bLua\x51\x00\x01\x04\x04\x04\x08\x00')
    elif mode == "5.1_64":
        w.data.extend(b'\x1bLua\x51\x00\x01\x04\x08\x04\x08\x00')
    w.write_proto(proto)
    with open(out_path, 'wb') as f: f.write(w.data)

def find_luadec():
    exes_path = EXES_DIR / "luadec"
    if exes_path.exists():
        if not os.access(exes_path, os.X_OK) and os.name != 'nt':
            try:
                exes_path.chmod(exes_path.stat().st_mode | 0o111)
            except:
                pass
        if os.access(exes_path, os.X_OK) or os.name == 'nt':
            return str(exes_path)
    exes_path = EXES_DIR / "luadec.exe"
    if exes_path.exists():
        return str(exes_path)
    return shutil.which('luadec')

def attempt_decompile(luadec_path, target_bytecode, output_lua):
    try:
        res = subprocess.run([luadec_path, target_bytecode], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        out_data = res.stdout.decode('utf-8', errors='replace')
        if res.returncode == 0 and len(out_data.strip()) > 10:
            with open(output_lua, 'w', encoding='utf-8') as f:
                f.write(out_data)
            return True, ""
        return False, res.stderr.decode('utf-8', errors='replace')
    except Exception as e:
        return False, str(e)

def fix_lua_syntax(content: str) -> str:
    # Keep UTF-8 strings (including Arabic); only apply the original syntax fixes.
    content = re.sub(r'#(\w+):', r'#\1.', content)
    content = re.sub(r'=\s*\.\s*end\b', '= nil', content)
    content = re.sub(r'\bend\.\s*$', 'end', content, flags=re.MULTILINE)
    return content

def remove_lua_comments(content: str) -> str:
    result = []
    i = 0
    n = len(content)
    in_string = False
    string_char = None
    in_long_string = False
    long_string_level = 0
    in_block_comment = False
    block_comment_level = 0
    while i < n:
        ch = content[i]
        if not in_string and not in_long_string and not in_block_comment:
            if ch == '-' and i+1 < n and content[i+1] == '-':
                if i+2 < n and content[i+2] == '[':
                    j = i+3
                    level = 0
                    while j < n and content[j] == '=':
                        level += 1
                        j += 1
                    if j < n and content[j] == '[':
                        in_block_comment = True
                        block_comment_level = level
                        i = j+1
                        continue
                    else:
                        i += 2
                        while i < n and content[i] not in '\r\n':
                            i += 1
                        continue
                else:
                    i += 2
                    while i < n and content[i] not in '\r\n':
                        i += 1
                    continue
        if in_block_comment:
            if ch == ']' and i+1 < n and content[i+1] == ']':
                j = i+2
                level_check = 0
                while j < n and content[j] == '=':
                    level_check += 1
                    j += 1
                if j < n and content[j] == ']' and level_check == block_comment_level:
                    in_block_comment = False
                    i = j+1
                    continue
            i += 1
            continue
        if not in_string and not in_long_string and (ch == '"' or ch == "'"):
            in_string = True
            string_char = ch
            result.append(ch)
            i += 1
            continue
        elif in_string:
            result.append(ch)
            if ch == '\\' and i+1 < n:
                result.append(content[i+1])
                i += 2
                continue
            elif ch == string_char:
                in_string = False
            i += 1
            continue
        if not in_string and not in_long_string and not in_block_comment and ch == '[' and i+1 < n and content[i+1] == '[':
            j = i+2
            level = 0
            while j < n and content[j] == '=':
                level += 1
                j += 1
            if j < n and content[j] == '[':
                in_long_string = True
                long_string_level = level
                result.append(content[i:j+1])
                i = j+1
                continue
        if in_long_string:
            result.append(ch)
            if ch == ']' and i+1 < n and content[i+1] == ']':
                j = i+2
                level_check = 0
                while j < n and content[j] == '=':
                    level_check += 1
                    j += 1
                if j < n and content[j] == ']' and level_check == long_string_level:
                    result.append(content[i+1:j+1])
                    i = j+1
                    in_long_string = False
                    continue
            i += 1
            continue
        result.append(ch)
        i += 1
    return ''.join(result)

def intelligent_decompile_file(in_path, out_path):
    luadec = find_luadec()
    if not luadec:
        return False, "luadec executable not found"
    proto = load_proto(in_path)
    if not proto:
        return False, "Failed to decode bytecode"
    modes = ["5.3_64", "5.3_32", "5.3_32_int4", "5.1_64", "5.1_32"]
    error_logs = []
    for mode in modes:
        with tempfile.NamedTemporaryFile(suffix='.luac', delete=False) as tmp_f:
            std_path = tmp_f.name
        try:
            build_std_file(proto, mode, std_path)
            ok, mode_err = attempt_decompile(luadec, std_path, out_path)
            if ok:
                with open(out_path, 'r', encoding='utf-8') as f:
                    original = f.read()
                cleaned = remove_lua_comments(original)
                cleaned_fixed = fix_lua_syntax(cleaned)
                with open(out_path, 'w', encoding='utf-8') as f:
                    f.write(cleaned_fixed)
                return True, f"Success with {mode}"
            error_logs.append(f"{mode}: {mode_err}")
        finally:
            if os.path.exists(std_path): os.unlink(std_path)
    return False, "\n".join(error_logs)

# ============================================================================
# COMPILATION LOGIC
# ============================================================================

class StdProto:
    __slots__ = ('src', 'ld', 'll', 'np', 'va', 'ms', 'ins', 'K', 'upvals', 'subs')
    def __init__(self):
        self.K = []
        self.subs = []

class StdReader:
    def __init__(self, data):
        self.d = data
        self.pos = 0
        self.sz_int = self.d[12]
        self.sz_size_t = self.d[13]
        self.sz_ins = self.d[14]
        self.sz_lua_int = self.d[15]
        self.sz_lua_num = self.d[16]
        self.fmt_int = '<i' if self.sz_int == 4 else '<q'
        self.fmt_size_t = '<I' if self.sz_size_t == 4 else '<Q'
        self.pos = 34
    def read(self, n): v = self.d[self.pos:self.pos+n]; self.pos += n; return v
    def byte(self): v = self.d[self.pos]; self.pos += 1; return v
    def int(self): v = struct.unpack_from(self.fmt_int, self.d, self.pos)[0]; self.pos += self.sz_int; return v
    def size_t(self): v = struct.unpack_from(self.fmt_size_t, self.d, self.pos)[0]; self.pos += self.sz_size_t; return v
    def lua_int(self): v = struct.unpack_from('<q', self.d, self.pos)[0]; self.pos += self.sz_lua_int; return v
    def lua_num(self): v = struct.unpack_from('<d', self.d, self.pos)[0]; self.pos += self.sz_lua_num; return v
    def string(self):
        sz = self.byte()
        if sz == 0: return None
        if sz == 0xFF:
            sz = self.size_t()
        length = sz - 1
        v = self.d[self.pos : self.pos + length]
        self.pos += length
        return v

def parse_std(r):
    p = StdProto()
    p.src = r.string()
    p.ld = r.int()
    p.ll = r.int()
    p.np = r.byte()
    p.va = r.byte()
    p.ms = r.byte()
    n = r.int()
    p.ins = []
    for _ in range(n):
        ins = struct.unpack_from('<I', r.read(r.sz_ins))[0]
        p.ins.append(ins)
    n = r.int()
    p.K = []
    for _ in range(n):
        t = r.byte()
        if t == 0: p.K.append((t, None))
        elif t == 1: p.K.append((t, r.byte()))
        elif t == 3: p.K.append((t, r.lua_num()))
        elif t == 19: p.K.append((t, r.lua_int()))
        elif t in (4, 20): p.K.append((t, r.string()))
        else: raise ValueError(f"Unknown const type {t}")
    n = r.int()
    p.upvals = r.read(n * 2)
    n = r.int()
    p.subs = [parse_std(r) for _ in range(n)]
    n = r.int(); r.read(n * r.sz_int)
    n = r.int()
    for _ in range(n): r.string(); r.int(); r.int()
    n = r.int()
    for _ in range(n): r.string()
    return p

class CustomWriter:
    def __init__(self, key):
        self.d = bytearray()
        self.key = key
    def write(self, b): self.d.extend(b)
    def byte(self, v): self.d.append(v & 0xFF)
    def i32(self, v): self.d.extend(struct.pack('<i', v))
    def string(self, b):
        if b is None:
            self.byte(0)
        else:
            if isinstance(b, str):
                b = b.encode('utf-8')
            sz = len(b) + 1
            if sz < 0xFF:
                self.byte(sz)
            else:
                self.byte(0xFF)
                self.d.extend(struct.pack('<Q', sz))
            enc = _xor(b, self.key)
            self.write(enc)

def write_custom(p, w):
    w.string(p.src)
    w.i32(p.ld)
    w.i32(p.ll)
    w.byte(p.np)
    w.byte(p.va)
    w.byte(p.ms)
    w.i32(len(p.ins))
    for ins in p.ins:
        w.write(struct.pack('<I', ins))
    w.i32(len(p.K))
    for typ, val in p.K:
        if typ == 'nil':
            w.byte(LUA_NIL)
        elif typ == 'bool':
            w.byte(LUA_BOOL)
            w.byte(1 if val else 0)
        elif typ == 'float':
            w.byte(LUA_NUM)
            w.write(struct.pack('<d', val))
        elif typ == 'int':
            w.byte(LUA_FLOAT)
            w.write(struct.pack('<q', val))
        elif typ == 'str':
            w.byte(LUA_STR)
            w.string(val)
        else:
            raise ValueError(f"Unknown constant type {typ}")
    n_upvals = len(p.upvals) // 2
    w.i32(n_upvals)
    w.write(p.upvals)
    w.i32(len(p.subs))
    for sub in p.subs:
        write_custom(sub, w)
    w.i32(0)
    w.i32(0)
    w.i32(len(p.locs))
    for nm, sp, ep in p.locs:
        w.string(nm)
        w.i32(sp)
        w.i32(ep)
    w.i32(len(p.upvs))
    for uv in p.upvs:
        w.string(uv)

def ensure_luac53():
    exes_luac = EXES_DIR / "luac5.3"
    if exes_luac.exists():
        if not os.access(exes_luac, os.X_OK) and os.name != 'nt':
            try:
                exes_luac.chmod(exes_luac.stat().st_mode | 0o111)
            except:
                pass
        if os.access(exes_luac, os.X_OK) or os.name == 'nt':
            return str(exes_luac)
    exes_luac = EXES_DIR / "luac"
    if exes_luac.exists():
        if not os.access(exes_luac, os.X_OK) and os.name != 'nt':
            try:
                exes_luac.chmod(exes_luac.stat().st_mode | 0o111)
            except:
                pass
        try:
            r = subprocess.run([str(exes_luac), '-v'], capture_output=True, timeout=3)
            if b'5.3' in r.stdout or b'5.3' in r.stderr:
                return str(exes_luac)
        except: pass
    for name in ['luac5.3', 'luac']:
        path = shutil.which(name)
        if path:
            try:
                r = subprocess.run([path, '-v'], capture_output=True, timeout=3)
                if b'5.3' in r.stdout or b'5.3' in r.stderr:
                    return path
            except: pass
    if os.name != 'nt':
        console.print("[yellow]luac5.3 not found. Installing lua5.3 via pkg...[/yellow]")
        try:
            subprocess.run(["pkg", "install", "lua5.3", "-y"], check=True)
            for name in ['luac5.3', 'luac']:
                path = shutil.which(name)
                if path:
                    return path
        except Exception as e:
            console.print(f"[red]Failed to install lua5.3: {e}[/red]")
    return None

def compile_file(src_path, out_path, original_bytecode_path):
    orig_source_path = src_path + ".orig"
    if os.path.exists(orig_source_path):
        with open(src_path, 'r', encoding='utf-8', errors='replace') as f_src:
            cur_source = f_src.read()
        with open(orig_source_path, 'r', encoding='utf-8', errors='replace') as f_orig:
            if cur_source == f_orig.read():
                shutil.copy2(original_bytecode_path, out_path)
                return True, "Unchanged – original bytecode copied"
    luac = ensure_luac53()
    if not luac:
        return False, "luac5.3 not found and could not be installed."
    original_proto = load_proto(original_bytecode_path)
    if not original_proto:
        return False, "Failed to parse original custom bytecode"
    with tempfile.NamedTemporaryFile(suffix='.luac', delete=False) as tf:
        tmp_out = tf.name
    try:
        result = subprocess.run([luac, '-o', tmp_out, src_path], capture_output=True, timeout=30)
        if result.returncode != 0:
            return False, f"Syntax error: {result.stderr.decode('utf-8', errors='replace')[:300]}"
        with open(tmp_out, 'rb') as f:
            std_bytecode = f.read()
        if len(std_bytecode) < 34 or std_bytecode[:4] != b'\x1bLua' or std_bytecode[4] != 0x53:
            return False, "Invalid bytecode generated"
        r = StdReader(std_bytecode)
        std_p = parse_std(r)
        new_ins = []
        for ins in std_p.ins:
            op = ins & 0x3F
            custom_op = STD_TO_CUSTOM.get(op, op)
            new_ins.append((ins & ~0x3F) | custom_op)
        new_K = []
        for t, v in std_p.K:
            if t == 0: new_K.append(('nil', None))
            elif t == 1: new_K.append(('bool', bool(v)))
            elif t == 3: new_K.append(('float', v))
            elif t == 19: new_K.append(('int', v))
            elif t in (4,20): new_K.append(('str', v.decode('utf-8')))
        original_proto.ins = new_ins
        original_proto.K = new_K
        def replace_subs(orig, new):
            if len(orig.subs) != len(new.subs): return
            for i in range(len(orig.subs)):
                orig.subs[i].ins = []
                for ins in new.subs[i].ins:
                    op = ins & 0x3F
                    custom_op = STD_TO_CUSTOM.get(op, op)
                    orig.subs[i].ins.append((ins & ~0x3F) | custom_op)
                orig.subs[i].K = []
                for t, v in new.subs[i].K:
                    if t == 0: orig.subs[i].K.append(('nil', None))
                    elif t == 1: orig.subs[i].K.append(('bool', bool(v)))
                    elif t == 3: orig.subs[i].K.append(('float', v))
                    elif t == 19: orig.subs[i].K.append(('int', v))
                    elif t in (4,20): orig.subs[i].K.append(('str', v.decode('utf-8')))
                replace_subs(orig.subs[i], new.subs[i])
        replace_subs(original_proto, std_p)
        w = CustomWriter(_K)
        write_custom(original_proto, w)
        with open(original_bytecode_path, 'rb') as f_org:
            org_data = f_org.read()
        orig_header = org_data[:34]
        new_proto = bytes(w.d)
        footer = org_data[34 + len(new_proto):] if len(org_data) > 34 + len(new_proto) else b''
        final_bytecode = orig_header + new_proto + footer
        with open(out_path, 'wb') as f:
            f.write(final_bytecode)
        return True, ""
    except Exception as e:
        return False, f"Compiler error: {e}"
    finally:
        if os.path.exists(tmp_out): os.unlink(tmp_out)



def run_decompile(input_file: str, output_file: str | None = None) -> int:
    src = Path(input_file).expanduser()
    if not src.is_file():
        print(f"ERROR: input file not found: {src}")
        return 2
    dst = Path(output_file).expanduser() if output_file else SOURCE_LUA_DIR / (src.stem + ".lua")
    dst.parent.mkdir(parents=True, exist_ok=True)
    ok, msg = intelligent_decompile_file(str(src), str(dst))
    if not ok:
        print("DECOMPILE FAILED:", msg)
        return 1
    # Snapshot original decompiled source so unchanged files can preserve original bytecode.
    shutil.copy2(dst, str(dst) + ".orig")
    print(f"DECOMPILED: {src} -> {dst}")
    print(f"SOURCE SNAPSHOT: {dst}.orig")
    return 0

def run_compile(source_file: str, original_bytecode: str, output_file: str) -> int:
    src = Path(source_file).expanduser()
    original = Path(original_bytecode).expanduser()
    dst = Path(output_file).expanduser()
    if not src.is_file() or not original.is_file():
        print("ERROR: source or original bytecode file not found")
        return 2
    if dst.resolve() == original.resolve():
        print("ERROR: output must not overwrite the original bytecode")
        return 2
    dst.parent.mkdir(parents=True, exist_ok=True)
    ok, msg = compile_file(str(src), str(dst), str(original))
    if not ok:
        print("COMPILE FAILED:", msg)
        return 1
    print(f"COMPILED: {src} -> {dst}")
    return 0

def main():
    ensure_dirs()
    parser = argparse.ArgumentParser(description="DAFAN RE PRO custom Lua 5.3 decompiler/compiler")
    sub = parser.add_subparsers(dest="action", required=True)
    d = sub.add_parser("decompile", help="custom bytecode -> Lua source")
    d.add_argument("input")
    d.add_argument("-o", "--output")
    c = sub.add_parser("compile", help="edited Lua source -> custom bytecode")
    c.add_argument("source")
    c.add_argument("original_bytecode")
    c.add_argument("output")
    args = parser.parse_args()
    if args.action == "decompile":
        return run_decompile(args.input, args.output)
    return run_compile(args.source, args.original_bytecode, args.output)

def run_from_app(action, input_path, output_path, original_path=None, app_dir=None):
    """Entry point called directly by the embedded Android Python runtime."""
    global BASE_DIR, LUA_BASE, ORIGINAL_LUA_DIR, SOURCE_LUA_DIR, EDITED_LUA_DIR, COMPILED_LUA_DIR, EXES_DIR
    if app_dir:
        BASE_DIR = Path(app_dir)
        LUA_BASE = BASE_DIR / "LUA_TOOL"
        ORIGINAL_LUA_DIR = LUA_BASE / "ORIGINAL LUA"
        SOURCE_LUA_DIR = LUA_BASE / "SOURCE LUA"
        EDITED_LUA_DIR = LUA_BASE / "EDITED LUA"
        COMPILED_LUA_DIR = LUA_BASE / "COMPILED LUA"
        EXES_DIR = BASE_DIR / "EXES"
    ensure_dirs()
    if action == "decompile":
        ok, msg = intelligent_decompile_file(str(input_path), str(output_path))
        if ok:
            shutil.copy2(output_path, str(output_path) + ".orig")
        return ("OK|" if ok else "ERROR|") + str(msg or ("تم فك الملف" if ok else "فشل فك الملف"))
    if action == "compile":
        if not original_path:
            return "ERROR|لم يتم تحديد ملف البايت كود الأصلي"
        ok, msg = compile_file(str(input_path), str(output_path), str(original_path))
        return ("OK|" if ok else "ERROR|") + str(msg or ("تمت إعادة التجميع" if ok else "فشلت إعادة التجميع"))
    return "ERROR|عملية غير معروفة"

if __name__ == "__main__":
    raise SystemExit(main())
