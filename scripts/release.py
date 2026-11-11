#!/usr/bin/env python3
"""long: 本机发布入口只从钥匙串或环境变量读取密码，避免密钥进入仓库、命令行及构建日志。"""
import argparse
import ctypes
import os
from pathlib import Path
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SIGNING = Path.home() / ".local/share/PdfReader/signing"
KEYSTORE = SIGNING / "PdfReader-release.p12"
SERVICE = b"PdfReader-release-signing"
ACCOUNT = b"long"


def keychain_password(create=False):
    security = ctypes.CDLL("/System/Library/Frameworks/Security.framework/Security")
    find = security.SecKeychainFindGenericPassword
    find.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_char_p, ctypes.c_uint32,
                     ctypes.c_char_p, ctypes.POINTER(ctypes.c_uint32),
                     ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p]
    find.restype = ctypes.c_int32
    length, data = ctypes.c_uint32(), ctypes.c_void_p()
    status = find(None, len(SERVICE), SERVICE, len(ACCOUNT), ACCOUNT,
                  ctypes.byref(length), ctypes.byref(data), None)
    if status == 0:
        try:
            return ctypes.string_at(data, length.value).decode("utf-8")
        finally:
            security.SecKeychainItemFreeContent.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
            security.SecKeychainItemFreeContent(None, data)
    if status != -25300 or not create:
        raise RuntimeError(f"钥匙串读取失败，状态码 {status}；未输出密码")
    password = secrets.token_urlsafe(36).encode()
    add = security.SecKeychainAddGenericPassword
    add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_char_p, ctypes.c_uint32,
                    ctypes.c_char_p, ctypes.c_uint32, ctypes.c_char_p, ctypes.c_void_p]
    add.restype = ctypes.c_int32
    status = add(None, len(SERVICE), SERVICE, len(ACCOUNT), ACCOUNT, len(password), password, None)
    if status != 0:
        raise RuntimeError(f"钥匙串写入失败，状态码 {status}；未生成密钥")
    return password.decode()


def signing_environment(create=False):
    env = os.environ.copy()
    if all(env.get(k) for k in ("READER_KEYSTORE_FILE", "READER_STORE_PASSWORD",
                                "READER_KEY_ALIAS", "READER_KEY_PASSWORD")):
        return env
    password = keychain_password(create)
    env.update(READER_KEYSTORE_FILE=str(KEYSTORE), READER_STORE_PASSWORD=password,
               READER_KEY_ALIAS="pdfreader", READER_KEY_PASSWORD=password)
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["init", "build", "certificate"])
    args = parser.parse_args()
    if args.action == "init":
        # long: 既有签名身份不得覆盖；后续版本必须使用同一证书才能覆盖升级。
        if KEYSTORE.exists():
            raise RuntimeError(f"签名已存在，保留原文件：{KEYSTORE}")
        SIGNING.mkdir(parents=True, exist_ok=True, mode=0o700)
        SIGNING.chmod(0o700)
        env = signing_environment(create=True)
        subprocess.run(["keytool", "-genkeypair", "-alias", "pdfreader", "-keyalg", "RSA",
                        "-keysize", "3072", "-sigalg", "SHA256withRSA", "-validity", "10000",
                        "-dname", "CN=PdfReader, OU=Android, O=long, C=CN",
                        "-storetype", "PKCS12", "-keystore", str(KEYSTORE),
                        "-storepass:env", "READER_STORE_PASSWORD", "-keypass:env", "READER_KEY_PASSWORD"],
                       env=env, check=True)
        KEYSTORE.chmod(0o600)
        print(f"已创建专用签名：{KEYSTORE}；密码保存在登录钥匙串 {SERVICE.decode()}")
    elif args.action == "build":
        env = signing_environment()
        subprocess.run([str(ROOT / "gradlew"), ":app:testReleaseUnitTest", ":app:lintRelease",
                        ":app:assembleRelease", "--console=plain"], cwd=ROOT, env=env, check=True)
    else:
        env = signing_environment()
        subprocess.run(["keytool", "-list", "-v", "-keystore", env["READER_KEYSTORE_FILE"],
                        "-alias", env["READER_KEY_ALIAS"], "-storepass:env", "READER_STORE_PASSWORD"],
                       env=env, check=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"发布准备失败：{error}", file=sys.stderr)
        sys.exit(1)
