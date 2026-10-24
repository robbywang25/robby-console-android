#!/usr/bin/env python3
"""Write the GitHub Release title and body for one version.

Usage: release_notes.py <version> <tests-run> <run-url> <title-out> <body-out>

The in-app update card shows the title's part after " · " and the body's first
paragraph, so both come from the version's ReleaseNotes entry unless
docs/releases/v<version>.md exists. That file is used as the body; a first line
"# v<version> · <topic>" sets the title instead. Either way the body ends with
the CI evidence and the attachment line.
"""
import pathlib, re, sys

NOTES = pathlib.Path('app/src/main/java/wang/robby/console/android/ReleaseNotes.java')
ATTACH = '附 APK 与 SHA256SUMS，Android 11 及以上，可覆盖旧版。'
STRING = r'"((?:[^"\\]|\\.)*)"'


def unescape(s):
    return re.sub(r'\\(.)', r'\1', s)


def entry(version):
    src = NOTES.read_text(encoding='utf-8')
    m = re.search(r'new Entry\("' + re.escape(version) + r'"\s*,\s*' + STRING + r'\s*,\s*' + STRING
                  + r'((?:\s*,\s*' + STRING + r')*)\s*\)', src)
    if not m:
        sys.exit(f'ReleaseNotes.java has no entry for {version}')
    return unescape(m.group(2)), [unescape(s) for s in re.findall(STRING, m.group(3))]


def main(version, tests, run_url, title_out, body_out):
    headline, items = entry(version)
    title = f'v{version} · {headline}'
    written = pathlib.Path(f'docs/releases/v{version}.md')
    if written.exists():
        lines = written.read_text(encoding='utf-8').strip().splitlines()
        if lines and lines[0].startswith('# '):
            title, lines = lines[0][2:].strip(), lines[1:]
        body = '\n'.join(lines).strip()
        body = body.removesuffix(ATTACH).rstrip()
        limits = ''
    else:
        body = headline + '。\n\n' + '\n'.join('- ' + item for item in items)
        limits = '\n\n限制与未验证：由 GitHub Actions 自动构建并发布，没有在 Find 上安装验证，也没有执行真实业务读写。仍为预览版。'
    evidence = (f'验证：GitHub Actions 自动构建（{run_url}）。{tests} 项 JVM 测试、Debug/Release 构建与 lint 通过；'
                '签名证书 SHA-256 与正式证书一致，Release 附件下载回读后校验通过。')
    pathlib.Path(title_out).write_text(title + '\n', encoding='utf-8')
    pathlib.Path(body_out).write_text(body + '\n\n' + evidence + limits + '\n\n' + ATTACH + '\n', encoding='utf-8')


if __name__ == '__main__':
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    main(*sys.argv[1:])
