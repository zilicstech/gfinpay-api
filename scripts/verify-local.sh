#!/usr/bin/env bash
# Local integration check against running fintech-svc (port 8090).
set -euo pipefail
API="${API:-http://localhost:8090}"
SUF="$(python3 -c "import time; print(str(int(time.time()))[-6:])")"
DM="88${SUF}00"
RM="77${SUF}00"
SM="98${SUF}00"

login() {
  curl -sf -X POST "$API/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"mobile\":\"$1\",\"password\":\"$2\"}"
}

echo "health: $(curl -sf "$API/actuator/health")"
ADMIN_JSON="$(login 9999999999 'Admin@123')"
TOKEN="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['data']['token'])" "$ADMIN_JSON")"
AUTH=( -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" )

DIST="$(curl -sf -X POST "$API/api/v1/admin/users/distributors" "${AUTH[@]}" \
  -d "{\"fullName\":\"North Hub\",\"mobile\":\"$DM\",\"password\":\"Dist@1234\",\"email\":\"$DM@fintech.local\"}")"
DID="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['data']['id'])" "$DIST")"
echo "distributor $DID mobile $DM"

RET="$(curl -sf -X POST "$API/api/v1/admin/users/retailers" "${AUTH[@]}" \
  -d "{\"fullName\":\"Kirana One\",\"mobile\":\"$RM\",\"password\":\"Retail@1234\",\"email\":\"$RM@fintech.local\",\"distributorId\":\"$DID\",\"shopName\":\"Andheri Kirana\"}")"
RID="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['data']['id'])" "$RET")"
echo "retailer $RID mobile $RM"

RJSON="$(login "$RM" 'Retail@1234')"
RTOKEN="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['data']['token'])" "$RJSON")"
RAUTH=( -H "Authorization: Bearer $RTOKEN" -H "Content-Type: application/json" )

TOP="$(curl -sf -X POST "$API/api/v1/wallet/topups" "${RAUTH[@]}" -d '{"amount":10000}')"
TID="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['data']['transactionId'])" "$TOP")"
curl -sf -X POST "$API/mock/pg/checkout/$TID/pay" >/dev/null
echo "topup settled $TID"
curl -sf "$API/api/v1/wallet" -H "Authorization: Bearer $RTOKEN"

SEND="$(curl -sf -X POST "$API/api/v1/dmt/senders" "${RAUTH[@]}" \
  -d "{\"mobile\":\"$SM\",\"fullName\":\"Walk-in Customer\"}")"
SID="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['data']['id'])" "$SEND")"
BENE="$(curl -sf -X POST "$API/api/v1/dmt/senders/$SID/beneficiaries" "${RAUTH[@]}" \
  -d '{"name":"Priya Sharma","accountNumber":"12345678901","ifsc":"HDFC0001234"}')"
BID="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['data']['id'])" "$BENE")"
IDEM="$(python3 -c 'import uuid; print(uuid.uuid4())')"
DMT="$(curl -sf -X POST "$API/api/v1/dmt/transactions" "${RAUTH[@]}" \
  -H "X-Agent-Id: $RID" -H "Idempotency-Key: $IDEM" \
  -d "{\"senderId\":\"$SID\",\"beneficiaryId\":\"$BID\",\"amount\":5000,\"transferMode\":\"IMPS\"}")"
TXN="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['data']['transactionId'])" "$DMT")"
echo "dmt initiated $TXN"
for i in 1 2 3 4 5 6 7 8 9 10; do
  STATE="$(curl -sf "$API/api/v1/transactions/$TXN" -H "Authorization: Bearer $RTOKEN" \
    | python3 -c "import json,sys; d=json.load(sys.stdin)['data']; print(d['state'])")"
  echo "poll $i $STATE"
  [[ "$STATE" == SUCCESS || "$STATE" == FAILED ]] && break
  sleep 1
done
curl -sf "$API/api/v1/admin/reports/summary" -H "Authorization: Bearer $TOKEN"
echo
psql -h localhost -d fintech -c "SELECT direction, amount, narration FROM ledger_entries WHERE transaction_id = '$TXN' ORDER BY id;"
