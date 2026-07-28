# Superherooo Web MVP Handoff

## What Was Built
- Public web app for Citizen and Partner at `https://www.superherooo.com/app/`.
- React/Vite source lives in the website repo under `Main/WebApp/`; built static files are committed under `Main/app/`.
- Backend web-launch changes are in this repo:
  - email OTP endpoints in `src/main/java/com/helpinminutes/api/auth/controller/AuthController.java`
  - MojoAuth/Spring Mail fallback in `src/main/java/com/helpinminutes/api/users/service/EmailVerificationService.java`
  - OTP-only task mode in `src/main/java/com/helpinminutes/api/tasks/model/TaskVerificationMode.java`
  - task lifecycle/photo skip logic in `src/main/java/com/helpinminutes/api/tasks/service/TaskService.java`
  - DB migration `src/main/resources/db/migration/V53__task_verification_mode.sql`

## Production Env Checklist
Do not print secrets in logs or chat. Verify these are set on the server:
- `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`
- `REDIS_URL`
- `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`
- `MOJOAUTH_API_KEY`
- `OTP_RETURN_IN_RESPONSE=false`
- `REALTIME_HTTP_PUBLISH_URL`, `REALTIME_HTTP_PUBLISH_SECRET`, `REALTIME_REDIS_CHANNEL`
- `FIREBASE_SERVICE_ACCOUNT_BASE64` or `FIREBASE_SERVICE_ACCOUNT_JSON`
- `AI_MODERATION_GEMINI_API_KEY` and/or `AI_MODERATION_GROQ_API_KEY`
- `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`
- SMTP/SES later: `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`

## End-To-End Test
1. Open `https://www.superherooo.com/app/`.
2. Sign up Citizen with email/password, send email OTP, verify OTP from email.
3. Create an instant task near Hyderabad with payment shown as Cash/UPI directly to Partner.
4. Sign up or login as Partner in another browser, verify email, ensure KYC is approved in admin/test DB, then go online with browser location.
5. Confirm Partner sees the nearby task, accepts it, and Citizen sees assignment.
6. Partner marks ARRIVED without selfie for web OTP-only task.
7. Partner starts work using Citizen arrival OTP.
8. Partner completes work using Citizen completion OTP.
9. Confirm Citizen sees completed state and Cash/UPI message.

## Test Credentials
- Do not commit real production credentials.
- For phone OTP reviewer/mobile flows, seeded reviewer numbers are `9999999991` Buyer, `9999999992` Helper, and `9999999993` Mediator with OTP `123456`.
- For web email/password testing, create temporary email accounts through `/app/signup`; OTP is delivered by MojoAuth. If OTP delivery is unavailable, verify `MOJOAUTH_API_KEY` on the server.
