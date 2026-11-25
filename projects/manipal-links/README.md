# Manipal Links
An unofficial, AI-assisted Manipal student project: discover people by purpose, send a pairing request, and start chatting when it is accepted.

## Try it without installation
Download and open `demo.html` in a browser. It uses fictional profiles and stores activity in that browser when browser storage is available, not in a shared database. If the preview blocks browser storage, your progress resets when it is closed.
1. In **Discover**, filter by Gym, Hackathon, Study, or Other and send a request.
2. Click the initials in the top right to switch to that post's fictional profile.
3. In **Requests**, accept the incoming request and open the new conversation.
4. Send a message and switch back to see the same conversation from the other side.

## Try it with two actual accounts on one computer
Install Node.js 22.13 or later. No dependencies or external services are needed. In the extracted folder, use PowerShell:

```powershell
$env:ML_INVITE_CODE="replace-with-your-own-long-private-code"
npm start
```

On macOS or Linux use:

```sh
ML_INVITE_CODE='replace-with-your-own-long-private-code' npm start
```

Open http://127.0.0.1:3000 in two separate browser profiles (e.g. one normal window and one private window). Create two pilot accounts using your invite code. Post from one account, request from the other, accept from the first account, then exchange messages. The site polls for new messages every few seconds. Data lives in a local SQLite file. No demo profiles are inserted in server mode. Do not commit the database or share the invite code publicly.

## What's implemented
- Purpose filters and text search, member-only posts, pairing requests and two-person conversations.
- Authenticated invite-only accounts with password hashing and server-side sessions.
- Server-side checks ensure only the recipient can accept, and only accepted participants can read or write messages.
- Run `npm test` to exercise pairing, message privacy, invitation checks, and duplicate requests through the HTTP API.
- Run `npm run demo` to generate the base standalone demo from the web assets. The included `demo.html` contains additional fictional data.

## Before a campus rollout
This is a local pilot, not a production student directory. It is not affiliated with MAHE. Before collecting data or opening registration, add student email verification, blocking/reporting and a moderation process, account recovery and deletion, clear privacy/retention terms, HTTPS, backups, and hosting. Use synthetic data in the demo and never ask people to post phone numbers, exact schedules, or other personal details. Leave the server bound to localhost during evaluation.
