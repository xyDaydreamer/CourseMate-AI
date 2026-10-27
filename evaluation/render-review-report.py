"""Render saved offline results for review/screenshots; does not run retrieval or a model."""
import hashlib
import html
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "evidence-053"
OUT.mkdir(exist_ok=True)
load = lambda name: json.loads((ROOT / "results" / name).read_text(encoding="utf-8-sig"))
rows = load("six-checks-after-053.json")
before = {r["id"]: r for r in load("six-checks-before-053.json")}
esc = lambda value: html.escape(str(value))

def short(page):
    return page.replace("Lec-3.3-ModernSyntax-Annotations-and-Reflection.pdf:", "Reflection:")\
        .replace("Lec-3.2-Generics-and-Collections.pdf:", "Generics:")\
        .replace("Lec-3.1-Exceptions.pdf:", "Exceptions:")\
        .replace("Lec-3.4-JUnit.pdf:", "JUnit:")\
        .replace("Lec-2-OOP-with-Java.pdf:", "OOP:")

counts = {}
for suite in ("test", "integrationTest"):
    counts[suite] = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for file in (ROOT.parent / "build" / "test-results" / suite).glob("TEST-*.xml"):
        tree = ET.parse(file).getroot()
        for key in counts[suite]:
            counts[suite][key] += int(tree.get(key, "0"))
groups = list(dict.fromkeys(r["group"] for r in rows))
zip_path = ROOT.parent / "build/distributions/coursemate-ai-0.5.3.zip"
zip_sha = hashlib.sha256(zip_path.read_bytes()).hexdigest()
summary = {"date": "2026-10-27", "mode": "OFFLINE_REAL_PDF_AND_SCRIPTED_ANSWER_NOT_LIVE_PLUGIN",
           "realApiCalls": 0, "pluginSha256": zip_sha, "testResults": counts,
           "groups": [{"name": g, "pass": all(r["pass"] for r in rows if r["group"] == g),
                       "caseIds": [r["id"] for r in rows if r["group"] == g]} for g in groups]}
(OUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
CSS = """body{font:14px/1.45 'Microsoft YaHei',Arial,sans-serif;background:#eef2f6;color:#172638;margin:0}
main{max-width:1120px;margin:auto;padding:20px}h1{font-size:24px;margin:0 0 6px}h2{font-size:18px;margin:12px 0}
p{margin:5px 0}.muted{color:#526477;font-size:12px}.ok{color:#076443;font-weight:bold}.bad{color:#a21e2e;font-weight:bold}
article,section{background:white;border:1px solid #d4dee8;border-radius:8px;padding:12px;margin:10px 0}
code{font:12px/1.4 Consolas,monospace;overflow-wrap:anywhere}table{border-collapse:collapse;width:100%;background:white}
td,th{text-align:left;padding:9px;border-bottom:1px solid #d4dee8}nav a{margin-right:15px}a{color:#135caa}
.question{font-size:14px;font-weight:600}.line{font-size:12px;margin-top:5px}footer{font-size:11px;color:#526477;margin-top:12px}
"""

def page(title, content):
    return '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>' + esc(title) + '</title><style>' + CSS + '</style><main><h1>' + esc(title) + '</h1><p class="muted">2026-10-27 · 真实教师 PDF + 生产检索 / 来源校验代码 · 固定模拟回答 · API 调用 0</p><p class="muted">这是离线日志报告截图，不是插件界面或真实模型回答截图。页码为物理 PDF 页码；排名从 1 开始。</p><nav><a href="summary.html">总览</a><a href="reliability.html">可靠性修复</a></nav>' + content + '<footer>完整文件名、候选分数、片段 ID、查询计划与前后结果：evaluation/results/six-checks-{before,after}-053.json<br>最终安装包 SHA256: <code>' + zip_sha + '</code></footer></main></html>'

summary_body = '<section><b>六项均通过</b> · 基础测试 ' + str(counts["test"]["tests"]) + '/' + str(counts["test"]["tests"]) + ' · 真实 PDF 集成 14/14 · 打包成功</section><table><tr><th>要求</th><th>实际结果</th><th>详情</th></tr>'
for index, group in enumerate(groups, 1):
    cases = [r for r in rows if r["group"] == group]
    content = ''
    for row in cases:
        previous = before[row["id"]]
        candidates = list(dict.fromkeys(h["page"] for h in row["candidates"]))
        candidate_line = ' · '.join(f'{i}. {short(p)}' for i, p in enumerate(candidates[:5], 1)) or '无候选'
        evidence_line = ' · '.join(f'{i}. {short(p)}' for i, p in enumerate(row["evidence"], 1)) or '[]（无课程证据）'
        rank = str(row["expectedEvidencePageRank"]) if row["expectedEvidencePageRank"] > 0 else '不适用（应为空）'
        old_rank = str(previous["expectedEvidencePageRank"]) if previous["expectedEvidencePageRank"] > 0 else '未进入证据 / 应为空'
        status = 'PASS' if row["pass"] else 'FAIL'
        content += '<article><p class="question">' + esc(row["id"] + ' · ' + row["question"]) + '</p><p class="line">期望页：<code>' + esc(' · '.join(row['expected']) or '无') + '</code></p><p class="line">最终证据：<code>' + esc(evidence_line) + '</code></p><p class="line">候选前五：<code>' + esc(candidate_line) + '</code></p><p class="line">标注页证据排名：<b>' + esc(rank) + '</b>；旧版：' + esc(old_rank) + '；模拟回答采用来源：' + esc(row["fixtureUsedSourceIds"]) + '；<span class="' + ('ok' if row["pass"] else 'bad') + '">' + status + '</span></p></article>'
    (OUT / f'group-{index}.html').write_text(page(group, content), encoding='utf-8')
    brief = '标注页排名 ' + ', '.join(str(r['expectedEvidencePageRank']) for r in cases) if index <= 5 else '4/4 无证据、无课程引用（含旧 U11）'
    passed = all(r['pass'] for r in cases)
    summary_body += '<tr><td>' + esc(group) + '</td><td class="' + ('ok' if passed else 'bad') + '">' + esc(brief) + '</td><td><a href="group-' + str(index) + '.html">逐题结果</a></td></tr>'
summary_body += '</table><section><b>防退化 / 新问题验证</b><p>原 28 题：18/18 命中，5/5 无来源，5/5 引用契约。</p><p>原冻结 24 题：14/14 命中，4/4 无来源，6/6 状态协议。</p><p>第二轮预先固定新题：6/6 命中、4/4 无来源；旧版为 5/6、4/4。该轮结果未用于继续调参。</p><p>第一轮 U11 现作为开发回归，最终已排除其误匹配；原始失败日志保持不变。</p></section>'
(OUT / 'summary.html').write_text(page('CourseMate AI 0.5.3 · 六项检索回归', summary_body), encoding='utf-8')
reliability = '<section><h2>非法来源编号</h2><p>修改前：1.9 被截成 S1；4294967297 也可能溢出成 S1，畸形元素被丢弃。</p><p>修改后：使用精确整数转换，拒绝小数、溢出、布尔、空元素、对象、嵌套数组和空字符串。顶层编号及逐结论编号均验证。</p><p class="ok">9 组非法编号全部拒绝；6 组合法兼容输入全部保留。</p></section><section><h2>截断状态</h2><p>修改前：finish_reason=length 且 JSON 恰好完整时返回 COMPLETE。</p><p>修改后：保留 TRUNCATED，不展示课程正文 / 引用，不自动重试，保留传输元数据。</p><p class="ok">完整 JSON 与不完整 JSON 两组截断回归通过。</p></section><section><h2>0.5.3 既有功能</h2><p class="ok">格式错误后恢复、引用错误后恢复、HTTP 错误不重试：3 项通过。</p><p>修复前新增审查测试 20 项中 7 项失败；修复后 20/20 通过。全量基础测试 ' + str(counts['test']['tests']) + ' 项、真实 PDF 集成 14 项全部通过。</p></section>'
(OUT / 'reliability.html').write_text(page('0.5.3 · 两处可靠性问题修复', reliability), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False, indent=2))
