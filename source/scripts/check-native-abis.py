"""校验 armeabi-v7a / arm64-v8a 原生库集合、ELF 架构与哈希；--write-lock 用于有意更新锁文件。"""
import argparse, hashlib, json, struct
from pathlib import Path

MACHINES = {"armeabi-v7a": 40, "arm64-v8a": 183}
parser = argparse.ArgumentParser()
parser.add_argument("--write-lock", action="store_true")
args = parser.parse_args()

root = Path(__file__).resolve().parents[1]
libs = root / "app/src/main/jniLibs"
lock_path = root / "native-abi-lock.json"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def actual(abi):
    folder = libs / abi
    if not folder.is_dir():
        raise SystemExit("缺少原生库目录：" + abi)
    return {path.name: path for path in folder.glob("*.so")}


if args.write_lock:
    libraries = {}
    for abi in MACHINES:
        libraries[abi] = {name: digest(path) for name, path in sorted(actual(abi).items())}
    if set(libraries["armeabi-v7a"]) != set(libraries["arm64-v8a"]):
        raise SystemExit("两个 ABI 的原生库文件名不一致，无法生成锁文件")
    lock_path.write_text(json.dumps({"abiLibraries": libraries}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("已写入 native-abi-lock.json：" + "、".join(sorted(libraries["armeabi-v7a"])))
    raise SystemExit(0)

lock = json.loads(lock_path.read_text(encoding="utf-8"))["abiLibraries"]
if set(lock) != set(MACHINES):
    raise SystemExit("锁文件 ABI 集合不正确")
if set(lock["armeabi-v7a"]) != set(lock["arm64-v8a"]):
    raise SystemExit("锁文件中两个 ABI 的原生库集合不一致")
for abi, machine in MACHINES.items():
    files = actual(abi)
    if set(files) != set(lock[abi]):
        raise SystemExit("原生库集合与锁文件不一致：" + abi)
    for name, path in files.items():
        data = path.read_bytes()
        if len(data) < 20 or data[:4] != b"\x7fELF" or struct.unpack_from("<H", data, 18)[0] != machine:
            raise SystemExit("原生库架构不匹配：" + abi + "/" + name)
        if hashlib.sha256(data).hexdigest() != lock[abi][name]:
            raise SystemExit("原生库哈希与锁文件不一致：" + abi + "/" + name)
print("原生库完整性校验通过：armeabi-v7a / arm64-v8a 各 " + str(len(lock["armeabi-v7a"])) + " 个")
