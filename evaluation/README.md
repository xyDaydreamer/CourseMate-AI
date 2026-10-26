# CourseMate AI 0.5.2 评测说明

这里保存题集、评测执行器和离线结果，供开发验证与人工复核使用。项目累计变更见 [README_CHANGES.md](../README_CHANGES.md)。这不是课程最终英文报告或 PPT。

## 当前结果及对应版本

| 项目 | 结果 | 记录 |
| --- | --- | --- |
| 0.5.2 当前基础 / 集成测试 | 137/137 + 10/10；打包成功 | [verification-0.5.2.json](verification-0.5.2.json) |
| 原有 28 案例，0.5.2 重跑 | Hit@3 18/18；无来源 5/5；引用契约 5/5 | [逐题结果](results/original-28-0.5.2.json) |
| 冻结 24 案例，0.5.2 重跑 | Hit@3 13/14；无来源 4/4；状态 / 协议 6/6 | [逐题结果](results/final-24-after-0.5.2.json)；未按该组调参，B12 仍未命中 |
| 0.5.1 基础单元测试 | 125/125 | [verification-0.5.1.json](verification-0.5.1.json) |
| 真实教师 PDF 集成测试 | 10/10 | 同上；讲义不随仓库提交 |
| 原有 28 案例，0.5.1 重跑 | Hit@3 18/18；无来源 5/5；引用契约 5/5 | [逐题结果](results/original-28-0.5.1.json) |
| 最终冻结 24 案例，0.5.0 阶段 | Hit@3 13/14；无来源 4/4；状态 / 协议 6/6 | [修改前](results/final-24-before-0.5.0.json)、[修改后](results/final-24-after-0.5.0.json)、[比较表](results/final-24-comparison-0.5.0.csv) |
| 典型缺陷 8 案例，0.5.0 阶段 | 有来源 6/6；无来源 2/2 | [修改前](results/defects-before-0.5.0.json)、[修改后](results/defects-after-0.5.0.json) |
| 真实 API 人工质量验收 | NOT_RUN | 用户另行执行；离线模拟不代替真实回答 |

`verification-0.5.0.json` 还记录冻结集前后 MRR、标注精度下界及安装包 / 题集指纹。0.5.1 没有重新测量冻结集；0.5.2 的实际重跑结果单独归档，不改名或覆盖旧版成绩。

归档前移除了旧测试汇总中的本机 hostname，并隐去了 SDK 验证的绝对沙盒路径；原有结果和计数保持不变。

## 题集和解释范围

- `questions.json`：原有 28 案例，18 道有来源题、5 道无来源题、5 个引用契约案例；已用于调优，属于固定回归。
- `holdout-20261027.json`：首次运行后参与过问题分析和修改，已成为开发回归，不再称独立盲测。
- `holdout-final-20261027.json`：冻结 24 案例，14 道有来源题、4 道无来源题、6 个状态 / 协议案例；未根据该次结果调参，但也不是独立人员标注的外部盲测。
- `regression-defects-20261027.json`：自然问法、代码措辞和无来源等 8 个典型缺陷复现。
- `baseline-13efb15.json`、`after-0.4.1.json`、`comparison.csv` 和 `verification-0.4.1.json`：历史阶段记录；其中部分状态和限制已经被后续版本替代，不能当作当前功能说明。

最终冻结集的前置比较版本是系统完善前的本地 0.4.1 快照；旧 28 题的历史比较基线为 `13efb15`。两者都不是直接运行远端 main 的质量对比。

预期来源使用从 1 开始的物理 PDF 页码，并非穷举所有相关页面。因此统计 Hit@3，不称 Recall@3。排序、标注页覆盖和精度下界辅助检查，但仍不能证明每个知识点都有证据。主要结论必须人工对照原文，来源 ID 合法不等于结论正确。

这些离线执行器使用真实 PDF 和检索代码、固定 fake 回答；真实回答正确性、结论 / 引用一致性保留 NOT_RUN。记录中的单次离线耗时不包含网络，不能当作稳定的端到端响应时间。已知 B12（反射缺点）仍未命中标注页。

## 重跑基础测试与离线回归

先配置 JDK 25 和 IDEA 2026.2.3；需要本地 IDE 时复制根目录 `local.properties.example` 并填写自己的路径。第一次构建可能需要下载依赖。

```powershell
# 在仓库根目录执行；不需要教师 PDF 或 API Key。
.\gradlew.bat test --console=plain --no-daemon

# 以下需要匹配版本的 9 份教师讲义。
$env:COURSEMATE_COURSE_FILES = '自己的课件目录完整路径'
.\gradlew.bat integrationTest --console=plain --no-daemon
.\gradlew.bat buildPlugin --console=plain --no-daemon
.\evaluation\run-evaluation.ps1 -PluginZip '.\build\distributions\coursemate-ai-0.5.2.zip' -CourseDir $env:COURSEMATE_COURSE_FILES
```

也可以使用 `start-coursemate.cmd -Mode Build`，会执行基础测试和打包；它不会代替单独的 `integrationTest`。`scripts/test-launcher.ps1` 为启动器参数回归，使用临时 fake Gradle，不调用模型、不打开 IDEA。

离线脚本需要 `JAVA_HOME` 或 `-JavaHome`，不会调用 API，并临时移除进程内 Key。默认结果写到被忽略的 `.coursemate/evaluation/latest.json`，不会覆盖 `after-0.4.1.json`。给 `-Output` 和 `-Label` 可以保存不同版本结果；归档前人工检查。

`IndependentEvaluation.java` 的三个参数依次是课件目录、题集 JSON 和输出 JSON；运行时 classpath 为安装包内 `lib` 下解压出的 JAR，使用 JDK 25 源文件模式。它用于冻结集 / 缺陷集；`LegacyHoldoutSnapshot.java` 仅用于与旧版安装包比较，不能与当前类型混用。历史安装包不在仓库中，不要为了重建基线覆盖当前修改。

## 真实 API 人工验收

有意进行付费验收时运行：

```powershell
.\evaluation\run-live-acceptance.ps1 -JavaHome $env:JAVA_HOME -CourseDir $env:COURSEMATE_COURSE_FILES
```

脚本默认使用 0.5.2 安装包；没有进程 Key 时在本机隐藏提示中输入。最多 6 次调用，不自动重试；遇到网络或配置失败会提前结束。这里没有自动执行该命令。

结果默认保存到被忽略的 `.coursemate/acceptance/live-acceptance.json`，可能包含选区代码与课程原文，不应直接提交。只保存回答、证据和安全错误分类，不保存 Key 或原始诊断响应。不要将 Key 发到聊天、源码或日志中。

人工逐条核对回答正确性、主要结论与引用原文的一致性、文件 / 页码、课程和通用补充的区分，以及实际端到端耗时。`live-review.csv` 是旧题集人工记录模板，未测项目保留 NOT_RUN。网络、认证、余额错误不能当作检索未命中，也不能计入回答质量通过率。
