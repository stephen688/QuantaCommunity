#!/usr/bin/env bash
# demo0 API 测试环境变量模板
#
# 用法：
#   cp env.example.sh env.sh
#   # 编辑 env.sh，执行 Phase 0 后填入 TOKEN 与各 ID
#   source env.sh
#
# 注意：env.sh 含 Token，勿提交到 Git（已在 .gitignore 中忽略时请确认）

# --- 服务地址（application.yml server.port=9191）---
export BASE_URL="http://localhost:9191"

# --- 鉴权 Token（Phase 0 / Phase 1 登录后填写）---
# USER_TOKEN:   POST /user/login  {"code":"test"}
# ADMIN_TOKEN:  将测试用户 is_admin=1 后重新登录
# USER_B_TOKEN: 第二普通用户（越权、关注等场景，需 code=test2 或手动造数）
export USER_TOKEN=""
export ADMIN_TOKEN=""
export USER_B_TOKEN=""

# --- 用户 ID ---
export USER_ID=""
export USER_B_ID=""

# --- 内容 / 互动 ID（Phase 2–5 造数后写回）---
export CONTENT_ID_PROF=""   # contentType=2 专业问答
export CONTENT_ID_LIFE=""   # contentType=1 生活求助
export QUESTION_ID=""       # 通常与 CONTENT_ID_PROF 相同（回答列表等）
export ANSWER_ID=""
export COMMENT_ID=""
export NOTIFICATION_ID=""
export IMAGE_URL=""         # POST /common/upload 返回

# --- 可选：举报 / 搜索历史等用例 ---
export REPORT_ID=""
export SEARCH_HISTORY_ID=""
