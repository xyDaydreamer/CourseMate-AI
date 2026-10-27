"""Render actual saved live-call evidence; never makes model calls."""
import html
import json
from pathlib import Path

root = Path(__file__).resolve().parent
raw = root.parent / '.coursemate/acceptance'
data = json.loads((root / 'results/live-content-review-053.json').read_text(encoding='utf-8'))
before = json.loads((raw / 'live-content-before-053.json').read_text(encoding='utf-8'))
final = json.loads((raw / 'live-boundary-final-053.json').read_text(encoding='utf-8'))
esc = html.escape
old_excerpt = before['results'][0]['answer'].split('\n\n')[0]
closed_lines = '\n'.join(line.strip() for line in final['results'][0]['answer'].splitlines()
                         if 'statement ends with a semicolon' in line or "call's parentheses are balanced" in line)
new_excerpt = final['results'][1]['answer'].split('\n\n')[-1]
assert closed_lines and 'unfinished method body' in new_excerpt
body = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<title>0.5.3 · 真实API复测与最终决策</title>
<style>body{{font:14px/1.5 'Microsoft YaHei',Arial;background:#eef2f6;color:#182735;margin:0}}
main{{max-width:1050px;margin:auto;padding:20px}}h1{{font-size:23px}}h2{{font-size:17px;margin:0 0 8px}}
section{{background:white;border:1px solid #d2dce7;border-radius:8px;padding:13px;margin:12px 0}}
.ok{{color:#086341;font-weight:bold}}.open{{color:#a02a28;font-weight:bold}}pre,code{{white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.5 Consolas,monospace}}
.muted{{color:#526477;font-size:12px}}</style><main>
<h1>0.5.3 · 真实 API 复测</h1><p class="muted">2026-10-27 · deepseek-flash · 助手逐项复核，非独立人工验收</p>
<section><h2>保留：两条通用代码边界提示</h2><p>分别核对内部调用 / 分号与外围代码块；字符串、注释内符号不参与边界判断。无特定方法替换规则。</p>
<p class="ok">最终安装包：原题与新回调题 2/2 针对性检查通过</p></section>
<section><h2>原始失败的实际回复</h2><pre>{esc(old_excerpt)}</pre></section>
<section><h2>最终版本的实际回复：原题</h2><pre>{esc(closed_lines)}</pre></section>
<section><h2>最终版本的实际回复：新题</h2><pre>void send() {{ sink.accept(")]}};");</pre><pre>{esc(new_excerpt)}</pre></section>
<section><h2>撤回：未证实稳定有效的并发 / 异常提示</h2><p>主要结论虽正确，额外实现细节仍把 ConcurrentMap 默认回调重试规则混入 ConcurrentHashMap。</p>
<p class="open">并发 / 异常事实问题仍未解决，未宣称全部回答质量通过。</p><p>不继续增加专门 API 规则或模型，不替换输出文本。完整候选包及失败回复全部保留。</p></section>
<section><h2>验证范围</h2><p>累计实际 HTTP 调用：{data['totalApiCalls']} 次；1 次来源字段格式重试已恢复；0 次 HTTP / 网络失败。</p>
<p class="ok">163 项基础测试 · 14 项真实 PDF 测试 · 六项检索回归 · 打包成功</p><p>没有自动推送。</p></section>
<p class="muted">这是浏览器渲染的实际测试日志报告，不是插件真实界面截图；生成式回答以后仍可能出错。</p>
<p class="muted">最终安装包 SHA256：<code>{data['finalPluginSha256']}</code><br>阶段记录：evaluation/results/live-content-review-053.json<br>原始回复：.coursemate/acceptance/live-*.json</p>
</main></html>'''
out = root / 'evidence-053/live-content-final.html'
out.write_text(body, encoding='utf-8')
print('Saved live-content-final.html from recorded API evidence.')
