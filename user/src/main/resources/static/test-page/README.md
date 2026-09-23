# MARVEL RUN User API Test Suite

관리자 기능 검증에 필요한 정상 사용자 신청/결제 데이터를 만들기 위한 정적 HTML 테스트 도구입니다.
최신 업로드 프로젝트의 `user` 모듈 공개 API 계약을 기준으로 구성했습니다.

## 실행

이 폴더 전체를 테스트 서버의 정적 파일 위치에 올리거나 HTTP(S) 정적 서버로 서비스한 뒤 `index.html`을 엽니다.
Toss 결제는 redirect 복귀가 필요하므로 `file://` 더블클릭 실행을 사용하지 마세요.

공통 설정 기본값:

- Backend URL: 현재 origin + `/api`
- Event ID: `test-000000`
- 결제 승인: `POST /v1/public/payments/confirm`

Toss 테스트 Client Key(`test_gck_...`)는 `payment.html`에서만 직접 입력하며 sessionStorage에 저장하지 않습니다.

## 페이지

- `personal-create.html` — 개인 최초 신청 + 최초 주문 생성
- `group-create.html` — 단체 최초 신청 + 최초 주문 생성
- `personal-lookup.html` — 개인 신청/결제/환불 상태 조회
- `group-lookup.html` — 단체 활성 명단/금융 상태 조회
- `personal-modify.html` — 개인 수정, 수정 환불/추가결제 주문 확인
- `group-modify.html` — 단체 최종 전체 명단 수정, 제거/추가/환불/추가결제 확인
- `personal-cancel.html` — 개인 전체 취소/환불
- `group-cancel.html` — 단체 전체 취소/환불
- `personal-additional-payment.html` — 관리자 수정 후 개인 추가금 주문 준비
- `group-additional-payment.html` — 관리자 수정 후 단체 추가금 주문 준비
- `payment-retry.html` — 실패 Payment 주문 재준비
- `payment.html` — Toss 인증 + `/v1/public/payments/confirm` 백엔드 승인

## 관리자 테스트용 추천 흐름

### A. 개인 결제 완료 데이터 만들기

1. `personal-create.html`
2. 새 테스트 참가자 값 생성
3. 신청 생성
4. `payment.html` 이동
5. Toss 테스트 결제 인증
6. 백엔드 승인
7. `personal-lookup.html`에서 `CONFIRMED`, 금액, Payment 상태 확인

이 상태를 관리자 서버의 환불/수정 테스트 원본으로 사용합니다.

### B. 단체 결제 완료 데이터 만들기

1. `group-create.html`
2. 인원수 지정 후 새 단체 테스트값 생성
3. 신청 생성
4. `payment.html`에서 단체 주문 한 번 결제/승인
5. `group-lookup.html`에서 활성 구성원/금액 확인

### C. 관리자 상향 수정 후 추가 결제 실측

관리자 기능에서 수정 결과가 `ADDITIONAL_PAYMENT_REQUIRED`가 된 뒤:

- 개인: `personal-additional-payment.html`
- 단체: `group-additional-payment.html`

에서 본인확인 → 부족액 주문 준비 → `payment.html` → Toss 실제 테스트 승인 순서로 진행합니다.
금액은 프론트에서 입력하지 않고 서버의 현재 `contractAmount - paidAmount` 계산값을 사용합니다.

### D. 사용자 경로 환불 회귀 테스트

- 개인 가격 하락: `personal-modify.html`의 수정 결과 `refunds` 확인
- 단체 일부 제거/가격 하락: `group-modify.html`의 `refunds` 확인
- 개인 전체 취소: `personal-cancel.html`
- 단체 전체 취소: `group-cancel.html`

관리자 환불 기능 자체를 측정할 때는 이 사용자 환불 페이지를 먼저 실행하지 말고, 결제 완료 데이터 상태를 유지한 채 관리자 API를 실행하세요.

## 주의사항

- 단체 수정의 `registrations`는 변경분이 아니라 **수정 후 남아야 할 전체 활성 명단**입니다.
- 기존 단체원은 `registrationId`를 유지하고 신규만 `null`이어야 합니다.
- Toss 성공 redirect만으로 결제 완료가 아닙니다. `payment.html`에서 백엔드 confirm 응답까지 확인하세요.
- `CONFIRMING`, `UNKNOWN`, 환불 `PROCESSING`, `UNKNOWN`은 실패 확정으로 간주하지 마세요.
- 이 도구는 Payment/Allocation을 DB에 직접 삽입하지 않습니다.

## 결제 orderId 재사용 방지

- Toss `orderId`는 결제마다 고유해야 하며, 승인/취소된 주문번호를 다시 결제에 사용하지 않습니다.
- `payment.html`은 Toss `requestPayment()` 호출 직전에 해당 주문을 `attemptedAt` 상태로 기록하고 같은 주문의 결제 버튼을 차단합니다.
- 승인 `COMPLETED` 후에는 테스트 도구의 현재 주문을 제거합니다. 다음 결제는 새 신청/수정/추가결제 준비/재준비 API에서 받은 새 주문으로 진행합니다.
- 실패·취소 복귀 후에도 화면에서 동일 orderId를 즉시 재사용하지 않습니다. 사용자 조회로 서버 상태를 확인한 후 `payment-retry.html`을 사용합니다.
