#!/bin/bash
# CPQ API test runner
set -u
BASE="http://localhost:8081/api/cpq"
C=/tmp/apitest/admin.cookie
OUT=/tmp/apitest/results.txt
rm -f $OUT
touch $OUT

run() {
  local name="$1"; local expect="$2"; shift 2
  local resp=$(curl -s -w "\n__HTTP__%{http_code}" -b $C "$@" 2>&1)
  local code=$(echo "$resp" | grep -oE "__HTTP__[0-9]+" | tail -1 | sed 's/__HTTP__//')
  local body=$(echo "$resp" | sed 's/__HTTP__[0-9]*$//')
  echo "=== $name" >> $OUT
  echo "EXPECT: $expect" >> $OUT
  echo "HTTP: $code" >> $OUT
  echo "BODY: $(echo "$body" | head -c 500)" >> $OUT
  echo "" >> $OUT
}

# 6.1 Auth
run "1.7 GET /auth/me 已登录" "200 + user data" "$BASE/auth/me"
run "1.8 修改密码缺失 oldPassword" "400" -X POST -H "Content-Type: application/json" -d '{"newPassword":"X"}' "$BASE/auth/change-password"

# 6.2 Customers
run "2.1 GET /customers 默认分页" "200 分页" "$BASE/customers"
run "2.2 GET /customers?page=0&size=5" "200" "$BASE/customers?page=0&size=5"
run "2.3 GET /customers?page=-1" "400 or 200" "$BASE/customers?page=-1&size=5"
run "2.4 GET /customers?size=99999" "400 or 限制" "$BASE/customers?size=99999"
run "2.5 GET /customers/invalid-uuid" "400/404" "$BASE/customers/invalid-uuid"
run "2.6 POST /customers 缺必填" "400" -X POST -H "Content-Type: application/json" -d '{}' "$BASE/customers"

# 6.3 Products
run "3.1 GET /products" "200" "$BASE/products"
run "3.2 GET /products?page=0&size=5" "200" "$BASE/products?page=0&size=5"
run "3.3 GET /processes" "200 (27 seed)" "$BASE/processes"
run "3.4 GET /internal-materials" "200" "$BASE/internal-materials"
run "3.5 GET /product-categories" "200 树形" "$BASE/product-categories"
run "3.6 POST /product-categories 缺失 code" "400" -X POST -H "Content-Type: application/json" -d '{"name":"X"}' "$BASE/product-categories"
run "3.7 GET /product-categories/bad-uuid" "400/404" "$BASE/product-categories/00000000-0000-0000-0000-000000000000"

# 6.4 Basic data config
run "4.1 GET /basic-data-config/sheets" "200" "$BASE/basic-data-config/sheets"
run "4.2 GET /basic-data-config/attributes missing sheetId" "400 or 空" "$BASE/basic-data-config/attributes"
run "4.3 GET /basic-data-config/derived missing sheetId" "400 or 空" "$BASE/basic-data-config/derived"

# 6.5 Comparison tags
run "5.1 GET /comparison-tags" "200 含 11 内置" "$BASE/comparison-tags"

# 6.6 Costing templates
run "6.1 GET /costing-templates" "200" "$BASE/costing-templates"
run "6.2 POST /costing-templates 缺 categoryId" "400" -X POST -H "Content-Type: application/json" -d '{"name":"T"}' "$BASE/costing-templates"

# 6.7 Customer templates
run "7.1 GET /templates" "200" "$BASE/templates"
run "7.2 POST /templates 缺 name" "400" -X POST -H "Content-Type: application/json" -d '{}' "$BASE/templates"
run "7.3 GET /templates/bad-uuid" "400/404" "$BASE/templates/00000000-0000-0000-0000-000000000000"

# 6.8 Quotations
run "8.1 GET /quotations" "200 分页" "$BASE/quotations"
run "8.2 GET /quotations?assignedToMe=true" "200" "$BASE/quotations?assignedToMe=true"
run "8.3 GET /quotations/bad-uuid" "400/404" "$BASE/quotations/00000000-0000-0000-0000-000000000000"
run "8.4 POST /quotations 缺 customerId" "400" -X POST -H "Content-Type: application/json" -d '{}' "$BASE/quotations"

# 6.12 Pricing / Datasource / Components
run "12.1 GET /pricing-strategies" "200" "$BASE/pricing-strategies"
run "12.2 GET /datasources" "200" "$BASE/datasources"
run "12.3 GET /components" "200" "$BASE/components"
run "12.4 GET /component-directories" "200" "$BASE/component-directories"

# 6.13 System
run "13.1 GET /users" "200" "$BASE/users"
run "13.2 GET /regions" "200" "$BASE/regions"
run "13.3 GET /departments" "200 树形" "$BASE/departments"
run "13.4 GET /approval-rules" "200" "$BASE/approval-rules"
run "13.5 GET /notifications" "200" "$BASE/notifications"
run "13.6 GET /notifications/unread-count" "200" "$BASE/notifications/unread-count"
run "13.7 GET /operation-logs" "200" "$BASE/operation-logs"
run "13.8 GET /health no-auth" "200" -c /dev/null "$BASE/health"

cat $OUT
