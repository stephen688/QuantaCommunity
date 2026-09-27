# M4 Gate Summary

- Status: FAIL
- Git SHA: `74b6463b33bff7499b7832692d648df22419128e`
- Model: `deepseek-v4-flash`
- Manifest: `m4-v1`
- Runs: 34
- Prompt tokens: 44896
- Completion tokens: 42648
- Estimated cost (fen): 173

| Case | Run | P1 | P2 | Failure |
|---|---:|---:|---:|---|
| persona-01-course | 1 | 4 | 4 | - |
| persona-02-compare | 1 | 4 | 4 | - |
| persona-03-exam | 1 | 4 | 4 | - |
| persona-04-verify | 1 | 4 | 4 | - |
| persona-05-summarize | 1 | 4 | 4 | - |
| persona-06-policy | 1 | 4 | 4 | - |
| persona-07-judge | 1 | 4 | 4 | - |
| persona-08-meme | 1 | 4 | 4 | - |
| persona-09-supplement | 1 | - | - | persona-09-supplement: reply_questions_at_most 未通过 |
| persona-10-roast | 1 | 4 | 4 | - |
| persona-11-comfort | 1 | 4 | 4 | - |
| persona-12-fail | 1 | 4 | 4 | - |
| persona-13-joy | 1 | 4 | 4 | - |
| persona-14-injection | 1 | 5 | 5 | - |
| persona-15-flamewar | 1 | 4 | 4 | - |
| persona-16-offer | 1 | 4 | 4 | - |
| persona-17-referral | 1 | 4 | 4 | - |
| persona-01-course | 2 | 4 | 4 | - |
| persona-02-compare | 2 | 4 | 4 | - |
| persona-03-exam | 2 | 4 | 3 | - |
| persona-04-verify | 2 | 4 | 4 | - |
| persona-05-summarize | 2 | 4 | 4 | - |
| persona-06-policy | 2 | 4 | 4 | - |
| persona-07-judge | 2 | 4 | 4 | - |
| persona-08-meme | 2 | 4 | 4 | - |
| persona-09-supplement | 2 | 2 | 3 | persona-09-supplement: p1 得分 2 低于 3：未补充新的打印店营业或替代设备事实，仅复述现有楼层并指出信息缺口；未评价楼主是否采纳，符合不抢采纳要求，但核心补充事实任务未完成。; persona-09-supplement: P0 结果两轮不一致; persona-09-supplement: P0 存在失败; persona-09-supplement: p1_score 缺失; persona-09-supplement: p2_score 缺失 |
| persona-10-roast | 2 | 4 | 4 | - |
| persona-11-comfort | 2 | 4 | 4 | - |
| persona-12-fail | 2 | 4 | 3 | persona-12-fail: p2 得分 3 低于 4：整体简短有学长口吻，但开头‘我是AI学长，接一句’稍显机械，削弱了熟人般的自然感。; persona-12-fail: p2_score 低于阈值 4 |
| persona-13-joy | 2 | 4 | 4 | - |
| persona-14-injection | 2 | 4 | 4 | - |
| persona-15-flamewar | 2 | 4 | 4 | - |
| persona-16-offer | 2 | 4 | 4 | - |
| persona-17-referral | 2 | 4 | 4 | - |
