# 📋 RM Store — Attendance App

An Android attendance app for **RM Store**: selfie + live GPS check-in, late-arrival detection, leave requests with yearly balances, overnight **Night Hold** reporting, and a multi-level manager hierarchy with approvals — all backed by a **Google Sheet**. No server or database to run, and APKs are built automatically by **GitHub Actions**.

> Android 7.0+ · Kotlin · Google Apps Script backend · Pay period **26th → 25th** · Timezone **Asia/Dhaka**

---

## 📸 Screenshots

Real screens from the app (Dark theme; Light uses the same layout on butter and cream). The brand name, logo and people's names are blurred.

**Getting in**

<table><tr>
<td align="center"><img src="docs/screenshots/login.jpg" width="190" alt="Sign in"><br><sub>Sign in</sub></td>
<td align="center"><img src="docs/screenshots/signup.jpg" width="190" alt="Create account"><br><sub>Create account</sub></td>
<td align="center"><img src="docs/screenshots/resetcode.jpg" width="190" alt="Verification code"><br><sub>Verification code</sub></td>
</tr></table>

**Every day**

<table><tr>
<td align="center"><img src="docs/screenshots/home.jpg" width="190" alt="Home"><br><sub>Home</sub></td>
<td align="center"><img src="docs/screenshots/attendance.jpg" width="190" alt="Attendance"><br><sub>Attendance</sub></td>
<td align="center"><img src="docs/screenshots/calendar.jpg" width="190" alt="Calendar"><br><sub>Calendar</sub></td>
<td align="center"><img src="docs/screenshots/leave.jpg" width="190" alt="Leave"><br><sub>Leave</sub></td>
</tr></table>

<table><tr>
<td align="center"><img src="docs/screenshots/nighthold.jpg" width="190" alt="Night Hold"><br><sub>Night Hold</sub></td>
<td align="center"><img src="docs/screenshots/menu.jpg" width="190" alt="Side menu"><br><sub>Side menu</sub></td>
<td align="center"><img src="docs/screenshots/notices.jpg" width="190" alt="Notices"><br><sub>Notices</sub></td>
</tr></table>

**Managers and admins**

<table><tr>
<td align="center"><img src="docs/screenshots/team.jpg" width="190" alt="My Team"><br><sub>My Team</sub></td>
<td align="center"><img src="docs/screenshots/admin-notices.jpg" width="190" alt="Admin: Notices"><br><sub>Admin: Notices</sub></td>
<td align="center"><img src="docs/screenshots/admin-report.jpg" width="190" alt="Admin: Report"><br><sub>Admin: Report</sub></td>
<td align="center"><img src="docs/screenshots/admin-targets.jpg" width="190" alt="Admin: Targets"><br><sub>Admin: Targets</sub></td>
</tr></table>

<table><tr>
<td align="center"><img src="docs/screenshots/admin-holiday.jpg" width="190" alt="Admin: Holiday"><br><sub>Admin: Holiday</sub></td>
<td align="center"><img src="docs/screenshots/admin-entry.jpg" width="190" alt="Admin: Entry"><br><sub>Admin: Entry</sub></td>
<td align="center"><img src="docs/screenshots/admin-app.jpg" width="190" alt="Admin: App"><br><sub>Admin: App</sub></td>
</tr></table>

---

## ✨ Features

### For every employee
- **Login / Logout / Change password** — Employee IDs (the sign-in name) live in a Google Sheet and passwords are stored there only as salted hashes. The app never keeps the password: it signs in once and uses an expiring token. Staff change their own password in the app (current password required, at least 8 characters). Five wrong passwords pause an account for 15 minutes. The login screen has **Keep me signed in on this device** (ticked by default): ticked, you stay signed in for 30 days and the sign-in renews itself every time you use the app; unticked, the sign-in ends after 12 hours (use this on a shared phone).
- **Attendance reminders in the notification bar** — from **8:30 AM** until 6:00 PM on working days, a person who has not marked attendance gets a phone notification (*Mark your attendance*, and from 9:10 AM *You have not marked attendance*); tapping it opens the attendance screen. It disappears once attendance is marked, and never appears on Friday, on a holiday, or during approved leave. **Managers** also get *👥 N team members not marked yet* with the names (from 9:10 AM, so nobody is chased during the grace period). At most one reminder of each kind per hour. Switch them off in the 👤 menu (*Attendance reminders*). Android 13+ asks for notification permission the first time. These are **checked by the phone about every 30 minutes** (Android picks the exact moment to save battery) — they are not instant push messages, and phones with an aggressive battery saver (Xiaomi, Oppo, Vivo, some Samsung) may delay them: set the app's battery use to *Unrestricted*. Real instant push would need Firebase.
- **Selfie attendance** — front-camera selfie (required, JPEG only, size-limited), live GPS location (fake/mock GPS is rejected), address lookup, and a **server-side** date/time stamp (changing the phone's clock does nothing).
- **Fast, reliable GPS** — one shared helper (`LiveLocation.kt`) keeps listening for a live fix instead of one try that can come back empty on a cold start; it shows the seconds while searching, tells you if the phone's Location switch is off, accepts a rougher fix after 10 seconds indoors, and gives a clear message after 45 seconds.
- **Location is required** — a submission without a real GPS location, or with one outside Bangladesh, is rejected by the server, not just the app.
- **Late Present** — there is a **10-minute grace period**: submitting up to 9:09 AM is on time, and at or after **9:10 AM** is marked *⏰ Late Present* with the time.
- **Absent** — still unmarked after **6:00 PM** counts as *Absent*; past unmarked days show as Absent too. **Friday is an automatic weekly off**, and so is any **custom holiday** an admin adds; neither is ever counted as absent.
- **Today's selfie on Home** — once you've checked in, the *Today's Status* circle shows your selfie as a small round picture (tap it to see it bigger) with where it was taken underneath.
- **One check-in per day** — a second submission on the same day is blocked.
- **Leave requests** — Casual, Sick, Earn or Paternity leave with a from/to date picker; days are counted automatically.
- **Leave application picture + reason (both required)** — attach a photo of your signed leave application (📷 *Take photo* or 🖼 *Choose picture*) **and** write a reason (3+ characters). A request can't be sent without both; the server refuses it too. The picture is shrunk on the phone and stored privately in the Drive folder with the selfies; you, your manager and every supervisor above you can open it from the leave request (*📎 View leave application*).
- **Leave balance** — by default **Casual 10 · Sick 14 · Earn 17 · Paternity 3** days per year (an admin can change them in *Admin Panel → 🌴 Leave*), with *left of total · used* shown per type. (The app has no gender field, so every employee gets the Paternity balance.)
- **Attendance ↔ leave conflict checks** — you can't mark attendance on an approved-leave day, or apply for leave on a day you already marked present.
- **Night Hold** — staying away overnight? Report it with:
  - live GPS (fake/mock locations are rejected),
  - **district → thana** pickers (all **64 districts**, with searchable lists),
  - **hotel name**,
  - a **summary at the top of the screen** for the current cycle (26th → 25th): the number of night holds and the last one, with **‹ ›** arrows to look at previous cycles; the **details below the form**: for each night the date/time, hotel, thana and district (tap one to open its location in Maps).
  - **only during the night-hold hours** (9:00 PM to 6:00 AM unless an admin changed them in *Admin Panel → 📝 Entry*) — outside those hours the Night Hold screen shows a *closed* notice and the server refuses a submission. It is **one per night**: a night is named by the date its evening started, so a 2:00 AM submission counts for the night before. The form can't be submitted without real data (checked again on the server).
- **Colours: Clay Brown + Soft Butter** — Clay `#6B352A` (header, buttons, text) and Butter `#FFF1A6` (chips, tiles, highlights) on a cream page; Dark mode is butter text on deep clay. To change the look, edit `res/values/colors.xml` (day), `res/values-night/colors.xml` (night) and the `NIGHT` table in `Theme.kt`. Red/green/amber status colours are unchanged.
- **Side menu and Day / Night colours** — the **👤** button on Home opens a side panel from the right edge (search, all menus with the unread-notice count, settings, a **☀️ Light / 🌙 Dark** switch, your name with **Log out**; the **›** button folds it into an icon strip). Light/Dark restyles the whole app and is kept per phone. Colours: `res/values/colors.xml` (day) and `res/values-night/colors.xml` (night) for the layouts, `Theme.kt` (`Pal`) for colours written in the screens, `SideMenu.kt` for the panel.
- **Labelled password fields and "Seek" search** — every password box (sign-in, create account, forgot / change password, and the admin "your own password" confirmations) is a *labelled field*: the label sits in the field as its placeholder, lifts into the top edge on focus while the outline parts around it, with soft 14 dp corners, an outline that turns accent-coloured, and a show/hide eye. Every search box (side menu, Admin → Users, My Team, the pick-one lists and the multi-pick list) is a *Seek* search: a search icon that becomes the field as one object — tap it and the field opens leftwards around the magnifier, the placeholder moves inside, **✕** clears, and an empty field folds back into the icon (lists inside pop-ups open by themselves). Code: `Fields.kt` (`FloatField`, `SeekField`), style `Widget.Sam.Field` in `themes.xml`, colours `field_line` / `field_focus` (Day and Night).
- **My Reports** — open **👤 menu → 📊 My Reports** to download your own **Attendance** or **Night hold** report as an Excel file (this pay period, last pay period or any dates, up to a year). A manager gets a *Whose report* choice: **👤 Only me** or **👥 My team** (their own report plus everyone below them in the chain). The server decides whose data you may have; nobody can get a report for someone outside their own line. Admins can still download everyone's in the Admin Panel.
- **🎯 Target vs Collection** — Home tile / 👤 menu: your target, what you collected, what remains and the %, with a day-by-day view. **Managers** also see their whole team (total + each person), **admins** see everyone. The top of the screen shows the **working days of the month: passed, remaining and total** (Fridays and the admin's holidays are not working days), and every person and the team total show the **RR = (target − collection) ÷ remaining working days**. The numbers come from the files an admin uploads (Admin Panel → 🎯 Targets).
- **📋 Surveys** — Home tile / 👤 menu: the questions your admin has sent. Open one, answer, **Submit** (once only; you can look at your answers afterwards). New ones show a badge. Questions can be short answer, paragraph, single choice, multiple choice, dropdown, rating (5 or 10), Yes/No, number, date or **📎 attachment (a picture from the camera or gallery)**. A survey can carry a picture from the admin, a deadline and an *Anonymous* mark (your name is then not saved with your answers).
- **Attendance screen** — the **TODAY'S STATUS** card from Home (status, present/absent for the 26th–25th, selfie, address and coordinates) is shown at the top. Once today is **Present / Late Present or Absent**, the camera, **Capture Selfie**, the live date/time/location box and **Submit Attendance** are hidden (only the status card stays); they show again while attendance can still be marked (not marked yet, leave/holiday/Friday keep the old behaviour). A day an admin opened for manual entry always shows the form. The result pop-up (✅ submitted / ⏰ Late / ℹ️ Already submitted) closes by itself after 5 seconds, or earlier with OK.
- **Manual entry list** — the side menu shows **Manual entry (N)** with every day an admin opened for you (newest day first, up to 120; a day you already filled in is not listed again).
- **Notices** — a notice from an admin (for everyone) or from **your manager (team notice)** appears as a card on Home, behind a 🔔 **bell** at the top (a red number shows how many you haven't opened), and as a **pop-up on Home every time you sign in** or open the app fresh. *Got it* closes it; *View all notices* opens the list.
- **Update from inside the app** — when an admin publishes a newer version, a blue **⬆️ Update available** card appears on Home (and an *Update app* item in the 👤 menu). Tap **Update**, the app downloads the new version (with a progress bar), checks it isn't damaged, and opens Android's installer. The first time, Android asks you to allow *Install from this source* for this app. Your data is not affected.
- **My Profile** — open it from the round photo at the top-left of Home, or from the 👤 menu → *My Profile*. You can:
  - **change your profile photo** (📷 *Take photo* or 🖼 *Choose picture*) — this is separate from your daily attendance selfie, and it replaces the letter in the round avatar on Home;
  - keep your **official phone**, **personal phone** and **email address**;
  - set an **emergency contact**: phone number plus **relation** from a drop-down (*Wife, Son, Brother, Mother, Father, Daughter, Other*). Choose **Other** and a box appears where you write the relation yourself;
  - choose your **Base Name** from a searchable list — the **Base Code** fills in by itself (a name used by several bases shows its code in the list) — and your **T-shirt size** (S, M, L, XL, XXL, 2XL).
  - add your **bike number**, and — **for managers only** — your **laptop model**.
  - The base list is the **Bases** tab of the Sheet (BaseCode, BaseName; made automatically with the 161 bases). Edit that tab to add, rename or remove bases. Saved in the Profiles tab columns K–M (BaseName, BaseCode, TShirt).
  
  Every field is optional (an emergency phone and its relation go together). Phone numbers are checked (digits, 8–15, optional `+`) and email addresses too. Only you can change your own profile.
- **Calendar** — the pay period (26th–25th) at a glance: present, late, leave, Friday off, 🎉 holidays, absent, and 🌙 night-hold days. Tap any date for details, including that day's night-hold status.

### For managers
- **Team notices** — on the Notices screen a manager has **✍️ Post a notice**: a title, a message and how many days to show it. It is visible to **that manager and everyone below them** only (not to their own boss or other teams), and only the manager who wrote it can remove it. Ordinary staff cannot post. Admins still post notices for everyone from the Admin Panel.
- **Team reports** — *My Reports → 👥 My team* downloads an Excel report for the manager and everyone below them.
- **Unlimited levels** — everything uses one *Manager* column, so Employee → ZM/ASM → RSM/DSM → SM → Operation Manager (or any structure) works with no hardcoded roles.
- **My Team** — each direct report's status today (Present / Late / On Leave / Absent / Off / Pending), their selfie and location, and a live roll-up for any report who manages a team.
- **Drill down** — tap any manager to see their team, all the way to individual employees.
- **Approve / Reject leave** — a supervisor can decide leave for **everyone below them**, direct reports and people further down the chain. Your Team screen lists all of their requests (tap a sub-manager to see just that team). Nobody can approve their own leave, and people outside your chain are refused by the server.

### For admins
An **admin** is any user whose **Role** cell in the Users sheet says `admin` (see *Make your first admin* below). Admins get an **Admin Panel** in the 👤 menu on Home with ten tabs (the tab bar scrolls sideways):
- **👥 Users** — a **search box** finds people by name, Employee ID or manager. **Add a new user** (Employee ID, name, manager, optional admin role) and **reset anyone's password**. You can type a temporary password or leave it blank and the server makes one (shown once, with a Copy button). The person is signed out everywhere and must choose their own password at their next sign-in. Creating a user or resetting a password asks for **your own password** again.
  Each user card shows the **app version installed on their phone** (e.g. *📱 v1.0.71 · seen 14 Oct*): green when it is the latest published version, orange *⬆ needs update* when it is older, and grey until that person has opened the new app once (the phone reports its version whenever Home loads; the sheet records it in Users columns H–J, *AppVersion / AppCode / LastSeen*, and writes only when something changed). The **📊 Report** tab builds a real `.xlsx` for **Attendance** or for **Night hold** for *this pay period*, *last pay period* or *any dates you choose* (up to a year), then you **Save to phone** or **Share** it. Sheet **Summary** has one row per person: *User ID, User Name, Period, Present, Late (part of Present), Leave, Absent, Day Off, Pending, Total Days*. Sheet **Daily Details** has one row per person per day: *date, weekday, Status (colour-coded), time, leave type, day-off reason (Friday / holiday name), location and coordinates*. *Night hold report:* sheet **Summary** (per person: night holds, how many were manual entries, days in the period) and sheet **Night Holds** (every record: night date, time, district, thana, hotel, location, normal/manual). *Attendance report:* a day counts as Present (attendance marked) before Leave (approved) before Day Off (Friday / holiday) before Absent; days that have not happened yet are not counted and today stays *Pending* until 6 PM. The sheets do not know when someone joined, so days before a new person's first day show as Absent. Every user card also has **📅 Calendar** — that person's pay-period calendar (read-only): each day's attendance with **time and location**, late days, leave, holidays, and night holds with **time, hotel and location**. Tap a date for details, and tap *🗺 …on map* to open the place in Maps. The admin sees *when and where*, but not selfies or leave-application pictures. And a **👤 Profile** button: the admin sees and can change that person's photo, phones, email, emergency contact and relation, bike number and laptop model (the laptop field follows the same managers-only rule). Changing a profile (or a photo) asks for **your own password** (then not again for 15 minutes). Admins can see everyone's *profile photo*, but not other people's selfies or leave applications unless they are above them in the Manager chain.
- **🌴 Leave** — set how many days a year each kind of leave gives (**Casual, Sick, Earn, Paternity**, whole numbers 0 to 365; start values 10 · 14 · 17 · 3). A *Save* shows what will change and asks to confirm; the new numbers apply to **everyone** straight away (Leave screen, Home, Calendar, and the server check when someone applies). Someone who already used more than the new number simply has **0 left**; leave already approved or pending is never changed. *Fill in the standard days* puts 10 · 14 · 17 · 3 back (press Save to apply). Stored in the Script Property `LEAVE_QUOTA`; a damaged value falls back to the standard days. Needs the new `Code.gs` deployed.
- **🧭 Menu** — set the **order** of the side menu on Home (*Menu* part and *Settings* part; *Home* always stays first) and of the **Admin Panel tabs**, with ▲ ▼ buttons. *Save the order* shows what changed and asks to confirm; it applies to **everyone** (phones pick it up when Home next loads) and the Admin Panel tab bar re-orders at once. *Standard order* puts the original order back (press Save to apply). The order is only a sequence: who may see or use an entry is still decided by Menu access and the role. Stored in the Script Property `MENU_LAYOUT`; a damaged value falls back to the standard order. Needs the new `Code.gs` deployed.
- **🔐 Menu access (on every user card)** — lock or unlock menus per person: Attendance, Leave, Night Hold, Calendar, Team, Notices, My Reports, Target vs Collection, Surveys, My Profile, Manual entry and, for admins, **🛠 Admin Panel** (you cannot lock your own, so another admin can always unlock you). A locked menu is greyed with a 🔒 on the phone and the server refuses its actions (so it cannot be bypassed). *Unlock all* resets a person; **🔐 Menu access for several people** sets one list for many people at once. Stored in Users column **K (Locked)**, e.g. `leave,reports`. Admin rights come from the Role column; the Admin Panel tick only switches the panel off for that admin.
- **🎯 Targets** — upload two Excel files: the **Target** file (Username, Name, Base Code, Base Name, Target) and the **Sale Collection report** (Collection Date, Collection Amount, Collection Type, Username, and also Customer Code / Customer Name = dealer, Base Code, Base Name, Name). The phone reads the `.xlsx` itself and sends the totals per day / person / type / dealer, so Dealer Code, Dealer Name, Base Code, Base Name and Name are saved too (tap a person, or the orange "no Username" note as admin, to see the collection by dealer). Rows without a Username cannot be given to anyone (the app tells you how many and how much). Needs your own password; a new upload replaces the old one of that kind. Choose which Collection Types count (for example leave out *Bank Charge*). The tab also sets the **period** (automatic = the whole month of the report, e.g. 1–31 Oct 2026, or your own dates) and whether *passed working days* count up to the last report date or today. Stored on the **TargetData** and **CollectionData** tabs.
- **⛔ Active / Inactive (on every user card, admin only)** — each card shows a green **Active** or red **Inactive** tag and a **Make Inactive / Make Active** button (asks to confirm). An inactive person cannot sign in ("Your account is inactive. Please contact your admin.", said only after a correct password), every phone where they are signed in is signed out at once, and they are left out of the people pickers (survey audience, Choose the people). Nothing is deleted. You cannot make yourself inactive. An inactive person is also **left out of team lists and the "total members" counts** (Team screen, Today's roll-up), **SMS/notification recipients**, the **attendance and night reports**, the **Target vs Collection** rows and the survey audience counts. If an inactive person has people of their own, those people move up to the next active manager above. The Users list header reads e.g. "158 ACTIVE USERS · 3 INACTIVE". Stored in Users column **L (Inactive)** (`1` = inactive; clear the cell to make active). **Make Inactive asks two questions: the _Last working day_ (a date, within a year of today) and _Remarks_.** Both are saved on the Users tab (columns **LastWorkingDay**, **InactiveRemarks**, **InactiveBy** = who and when; created automatically) and shown on the person's card. An **e-mail is sent automatically to every active line manager above the person (their manager, that manager's manager, …) and to every active admin**: name, Employee ID, last working day, remarks, who did it. People with no e-mail in My Profile, and inactive managers/admins, are skipped (the app tells the admin how many were e-mailed and how many have no e-mail). If the mail service fails or the daily quota is used up, the account is still made inactive. Making someone Active again clears the date/remarks and sends no mail. The account is switched off immediately; the last working day is a record, not a timer. Needs the same mail permission as the other e-mails (`authorizeEmail`).
- **📋 Surveys (Manage surveys)** — in the Surveys screen, tap **⚙️ Manage surveys**. Admins can always do this; any other person can be allowed in **Users → 🔐 Menu access → 📋 Can create & manage surveys** (they then see the button, can create, edit, close, delete and read the results of every survey, but get no other admin power; the right is stored as `+surveys` in Users column K, next to the locks).
  **🪪 Team members (line managers)** — the same dialog has a second extra right, **Can see Profile & Calendar of own team** (stored as `+teamview`). A line manager with it gets **🪪 Team members** in the 👤 menu: a searchable list of ONLY the people below them (any depth) with 👤 Profile and 📅 Calendar buttons, **read only** (no editing, no admin power; the server checks the right and that the person is really below them). **➕ New survey**: title, explanation, optional picture, optional deadline, who answers (everyone or chosen people), Anonymous, and the questions (nine kinds + picture attachments; up to 30 questions, 5 of them attachments; reorder with ↑ ↓). Tap a survey for **Results** (totals and percent bars, average of ratings/numbers, written answers, attachment thumbnails, who has not answered yet, **📥 Download Excel**), **Edit**, **Close / Open again** or **Delete** (needs your password; also removes the answers and pictures). Once someone has answered, the questions and the Anonymous choice can no longer be changed (wording of the title, deadline, audience and picture can). Surveys for everyone can post a notice. Stored on the **Surveys** and **SurveyResponses** tabs; attachment pictures go to the same private Drive folder as selfies. Menu access key: `survey`.
- **📢 Notices** — post a title + message to everyone (shown for 7 days by default, up to 90) and remove notices. Tick **📱 Also send as an SMS** to text it to everyone's phone too (see **📱 SMS** below).
- **🎉 Holiday** — pick a date and a name; that day behaves like Friday for everyone (nobody is absent, the team view shows *Off*, Calendar and Home show the holiday name). Remove a holiday and the day counts as a normal working day again.
- **📝 Entry** — two tools:
  - **Night hold hours.** Choose when people may submit a night hold (for example *9:00 PM → 6:00 AM*, or *8 PM → 5 AM*). If the closing hour is earlier than the opening hour the window runs through midnight. The server enforces it and every phone shows the new hours.
  - **Manual entry for a chosen date.** Open **one past date (or today)** for **attendance** or for a **night hold**, and choose **who** may fill it in: **everyone**, **a whole team** (a manager and everyone below), or **selected people** (searchable list). Optionally set the **time to record** (24-hour, e.g. `09:00`); empty = the real time. Needs **your own password**. The chosen people see **📝 Manual entry** in their Home 👤 menu, pick the date, and fill the normal screen (live selfie and GPS are still required). Each person can fill each opened date **once**; entries are marked **[Manual]** in the Address column, and the opened dates are kept on a **ManualEntry** tab. Switch a date off with ✕ and people who have not filled it in yet lose it at once.
- **🆕 Create account (login page → "New here? Create account")** — opens a full-screen **Create your account** page in three steps (the same look as the sign-in page): **1 Details** (full name, **Employee ID**, e-mail, password, confirm password) → **2 Verify e-mail** (an animated padlock and six code boxes: each digit pops into its box, and at the sixth the boxes tilt, melt into one ball and are checked automatically — no Confirm button. A correct code turns the ball green, the padlock opens and becomes a green check with "Verified and secure"; a wrong one turns it red, shakes it and opens six empty boxes again. "Send a new code" after 30 seconds, "Change my details" to go back; both are locked while a code is being checked) → **3 Done**. The **6-digit e-mail code** proves the address (10 minutes, once, 5 tries). The request then waits on the **SignupRequests** tab (the password is kept only as a hash) and **nobody can sign in until an admin approves**. Admin Panel → Users → **📝 Sign-up requests (N waiting)**: **Approve** (asks for your password and the line manager; creates the account, saves the e-mail in My Profile, e-mails the person) or **Reject** (e-mails them), and a tick box **Let people create accounts on the login page** switches sign-up on/off. Limits: 3 tries per hour per username and per e-mail, 60 per day for the whole app, at most 100 waiting. Needs the same mail permission as e-mail reports (`authorizeEmail`).
- **🔔 Animated notice bell** — the bell at the top of Home swings like a ringing bell (hanging from its top, the swings getting smaller) and then the red count badge pops in with a small bounce. It plays when Home opens with unread notices and again whenever a new notice arrives while the app is open. It is drawn as a vector icon (`ic_bell.xml`) in Soft Butter, animated by `BellAnim.kt`.
- **🔔 Notification sound** — the attendance reminders in the notification bar play the app's own short rising "ding-ding" chime (`res/raw/notify_chime.wav`) and vibrate twice. The same chime sounds inside the app when a new notice arrives (the pop-up after sign-in, or a higher unread count on the bell after a refresh). It follows the phone's notification volume and silent mode; on Android 8+ the sound can also be changed per channel in **Phone Settings → Apps → RM Store → Notifications → Attendance reminders**. The reminders use a new channel (`attendance_reminders_v2`) because a channel's sound can only be set when it is created; the old channel is removed automatically.
- **🔑 Forgot password? (login page)** — the person types the Employee ID; a **6-digit code is e-mailed** to the address saved in **My Profile → Email**, then they enter the code with the same animated padlock-and-boxes screen as sign-up (the sixth digit is checked at once: a good code opens the padlock and shows a green check, a wrong one shakes red and costs a try; the server action `resetCheck` only checks the code, it neither uses it up nor changes anything) and then choose a new password (`resetConfirm` checks the code once more with the password). The code works for **10 minutes**, only once, is cancelled after **5 wrong tries**, and is stored only as a salted hash (script cache). At most **3 codes per account per hour**. The answer is the same for every username (nobody can find out who has an account), nobody without an e-mail in My Profile or an inactive person gets a code (they ask an admin to reset). A correct code ends every old sign-in, lifts a "too many wrong passwords" pause and sends a "your password was changed" mail. It uses the same mail permission as the e-mail reports: run **`authorizeEmail`** once in the Apps Script editor (and keep a few mails of the daily quota free).
- **✉️ E-mail reports (in 📊 Report)** — send **every person their own** attendance and/or night hold report as Excel attachment(s), for the chosen period, to everyone, a whole team or selected people. Addresses come from **My Profile → Email**; people without one are skipped and counted. Needs your own password; the app shows how many mails will go and how many are left today (about 100/day on a normal Gmail account, 1500 on Workspace) and sends in groups of 10 with progress. One-time: deploy the new `Code.gs` and run the function `authorizeEmail` once in the Apps Script editor. Each send is logged on the **EmailLog** tab (no addresses). Nobody ever receives another person's data.
- **📱 SMS** — text messages to people's phones through [SimGate](https://simgate.app) (`https://api.simgate.app/v1/sms/send`): *your own spare Android phone with a SIM* runs the SimGate app and does the sending. Set it up here (API key, Device ID, monthly safety limit, **your own password**), see *used / left this month*, **send a test to yourself**, and send any message (up to 300 characters) to **everyone**, **a whole team** or **selected people**. A person's number is the **Official phone** in *My Profile* (else the Personal phone); Bangladesh `01XXXXXXXXX` becomes `+8801XXXXXXXXX`; one text per number; people without a number are skipped and counted. A send that would go over the monthly limit is refused. The **API key and Device ID live only in the script's private Script Properties** — never in the app, the Sheet or GitHub (the app only shows `••••` + the last 4 characters). Every send is listed on the **SmsLog** tab (time, who sent, to whom, sent/failed/no-phone counts, message; no numbers or key). Managers can tick **📱 Also send as an SMS to my team** when posting a team notice (their team only). One-time setup: install the SimGate app on the spare phone and register the device → copy the API key and Device ID → paste the new `Code.gs` and deploy a **New version** → run the function `authorizeSms` once in the Apps Script editor and approve → Admin Panel → 📱 SMS → paste key + Device ID + your password → Save → *Send a test text to my phone* (your own Official phone must be in My Profile).
- **📲 App** — **publish a new app version for everyone.** Download the new APK from GitHub onto your phone, tap *Upload new version (APK)*, pick the file, add a short *What's new* note, confirm with your own password, and wait for the progress bar. The app reads the version out of the APK itself and refuses a file that isn't this app. Everyone signed in is then offered the update on Home. *Stop offering this update* withdraws it. **Compulsory updates:** when publishing, the box **Compulsory: people cannot use the app until they install it** is ticked by default. A phone with an older build then shows only a full-screen **Update required** page (version, what's new, **Download and install**, **Check again**; the Back button does nothing) instead of any other screen, until the new build is installed. A phone learns about it the next time it opens or refreshes Home, and the requirement is remembered, so it also holds after closing the app or when offline. On the published version's card, **Make it optional / Make it compulsory** switches it later (needs your password), and phones on the Update required page are released when they tap *Check again* or reopen the app. *Stop offering this update* releases everyone too. Phones that still run a build from before this feature only see the normal optional card, so they must install this version once the normal way. Make sure the APK is signed with the same key as the installed app: Android refuses a different one and a compulsory update would then leave people stuck (if that happens, remove the update, or delete the `APP_UPDATE` Script Property).

The notice pop-up is shown by the app when it is open. It is **not a phone push notification** (no sound or banner while the app is closed); that would need Firebase.

---

## 🎨 Look and feel

The app uses a **glass / gradient** style: a soft indigo → lavender gradient behind every screen, frosted white cards with big rounded corners, white pill buttons, a **floating** bottom bar, and on Home a **Quick Actions** row of pastel tiles (Night Hold, Calendar, Reports) beside two big pills (Attendance, Leave). Everything is driven by a few shared resources, so the whole app can be re-coloured in one place: `res/values/colors.xml` and `themes.xml` (colours and the `GlassCard`, `Btn`, `BtnWhite` styles), `res/drawable/bg_app.xml` (the gradient), `bg_nav.xml` (floating bar) and `bg_tile_*.xml` (the pastel tiles).

---

## 📱 Screens

| Screen | What it does |
|---|---|
| **Home** | Fixed header with clock and a 🔔 notice bell, today's status (with your round selfie), notices, leave-balance bars, and this month's night holds |
| **Attendance** | Selfie + location capture and submit |
| **Leave** | Apply for leave (reason + application picture), see balances and request history |
| **Team** *(managers only)* | Team status, roll-ups, drill-down, leave approvals |
| **Calendar** | Pay-period view of attendance, leave and night holds |
| **Night Hold** | Overnight stay report with location, district/thana and hotel |
| **My Profile** | Change your photo; phones, email, emergency contact + relation, Base Name (+ Base Code), T-shirt size, bike number, laptop model (managers) |
| **Notices** | Everything admins and your manager have posted; managers can post a team notice |
| **My Reports** | Download your own (managers: or your team's) attendance / night hold Excel file |
| **Admin Panel** *(admins only)* | Add users, reset passwords, post notices, add holidays, publish app updates |

A **bottom bar** (Home · Attendance · Leave · Team · Calendar · Night Hold) is on every screen, and every screen has a compact header that stays fixed while the content scrolls. **Pull any screen down and let go to refresh it** (a round spinner shows while it loads; there is no refresh button; `Ui.kt` `pullToRefresh`, `androidx.swiperefreshlayout`), and the 👤 menu on Home has *My Profile*, *Change Password*, *Admin Panel* (admins only), *Logout* and the app version.

---

## 🛠 Tech

| Part | Technology |
|---|---|
| App | Kotlin, AppCompat, Material Components |
| Camera | CameraX |
| Location | Google Play Services (fused location) |
| Backend | Google Apps Script web app (`Code.gs`) |
| Data | Google Sheets (+ Google Drive for selfies, leave-application pictures and the published app update) |
| Build | GitHub Actions → signed release APK |

Speed: screens open instantly from a saved copy of the last response and then refresh; selfies are downloaded once and cached; selfies and leave-application pictures are shrunk before sending.

---

## 🚀 Setup

### 1. Google Sheet + Apps Script
1. Create a new Google Sheet.
2. **Extensions → Apps Script**, paste in `Code.gs`, and save.
3. If the script is **not** opened from inside the Sheet, set `SHEET_ID` at the top of `Code.gs` to the ID in the sheet's URL (the text between `/d/` and `/edit`).
4. Run the **`setup`** function once and approve the permission prompts. It creates:
   - **Users** — `Username | Password | Name | Manager | TokenEpoch | Role | MustChange` (*Manager* = another user's username, blank if none; this builds the hierarchy. *Password* holds a hash after the first sign-in. *Role* is `admin` for admins and blank for everyone else. *TokenEpoch* and *MustChange* are managed by the script, leave them alone)
   - **Attendance** — filled in as staff check in
   - **Leaves** — filled in as staff apply; the **Status** column (Pending / Approved / Rejected) is set by a manager in the app, or by hand. The **Application** column (added automatically) holds the Drive file id of the attached leave-application picture; **DecidedBy** and **DecidedOn** (added automatically on the first decision) record who approved or rejected it and when. In the app, pending applications are always listed first (My Leave and Team), and decided ones show "✅ Approved by <name> · date"
   - **NightHold**, **Notices**, **Holidays**, **SmsLog**, **EmailLog** and **Profiles** — created automatically on first use (admins add notices and holidays from the app; you can also read them in the Sheet)
5. Add your staff to **Users** and link the **Manager** column. You can type a plain password for a new person; it is replaced by a hash the first time they sign in.
   - **Upgrading an existing sheet?** Make a backup copy of the Sheet, then run **`upgradeSecurity`** once. It hashes every plain-text password and creates the token secret. It is safe to run again. Delete the backup afterwards, and remember that Google Sheets *Version history* still holds the old plain-text values.
6. **Make your first admin:** open `Code.gs`, add a Script Property `MAKE_ADMIN_USER` (Project Settings → Script Properties) with your own username, select `makeAdmin` and press **Run** (or type `admin` into the *Role* cell yourself). Only someone who can edit the Sheet can do this, so an admin can't be created from the app by an ordinary user. After that, admins add other users from the app.
7. **Deploy → New deployment → Web app** — *Execute as* **Me**, *Who has access* **Anyone**. Copy the URL ending in `/exec`.

### 2. Connect the app
Open `app/src/main/java/com/example/attendance/Api.kt`, paste the URL into `Config.SCRIPT_URL`, and commit.

### 3. Signing key (one-time)
Releases are signed with a private key that is **never** stored in the repo.
1. On a computer with Java, create the key (keep the file and both passwords somewhere safe — **if you lose it you can never update the app again**, everyone would have to uninstall and reinstall):
   ```
   keytool -genkeypair -v -keystore sambond-release.jks -alias sambond -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Turn it into text: `base64 -w0 sambond-release.jks` (Linux), `base64 -i sambond-release.jks | tr -d '\n'` (macOS), or in PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes("sambond-release.jks"))`.
3. In GitHub: **Settings → Secrets and variables → Actions → New repository secret**, add four secrets: `KEYSTORE_BASE64` (the text from step 2), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`sambond` above) and `KEY_PASSWORD`.

### 4. Build the APK
- Push to the repo or run **Actions → Build APK → Run workflow**.
- Download the APK from the run's **`app-release`** artifact (a zip containing the APK), or from the repo's **Releases** page (easiest on a phone). Builds signed with your own key are published as normal releases; temporary-key builds are saved as a **draft** release that only you can see, because repositories with *immutable releases* refuse files on a pre-release.
- Install it on an Android phone (Android 7.0+).

---

## 🔢 Versions & updates

- Every build is numbered **`1.0.<run number>`** (for example `1.0.56`). The APK is named `RM-Store-Attendance-v1.0.56.apk` and is also published on the **Releases** page.
- The version shows on the login screen and in the 👤 menu on Home.
- All builds are signed with **one private key** kept in GitHub Secrets (see *Signing key*), and each build has a higher version number than the last — so a new APK installs **over** the old one as a normal update.
- ⚠️ **One-time step:** an APK signed with an older key (the previous public debug key) can't be updated in place. Android shows *"Conflicting app signatures"*. Uninstall the old app once, then install the new one. After that, updates just work. Your data is safe — it all lives in the Google Sheet.
- If the four signing secrets are set, every APK is signed with your key. If they are **missing**, the build still runs but with a throw-away key: the file is named `…-TEMP-KEY.apk`, the run page shows a ⚠️ warning, and it can only be installed fresh (uninstall the old app first). If a secret is wrong the build **fails with a clear message**.

### Updating the app for everyone (in-app update)
1. Build the new APK (**Actions → Build APK → Run workflow**) and download it to your phone from the **Releases** page.
2. In the app: 👤 menu → **Admin Panel → 📲 App → Upload new version (APK)**, pick the downloaded file, confirm.
3. Everyone who opens the app sees **⬆️ Update available** on Home and installs it in two taps.

Requirements and limits:
- The APK must be signed with **the same key** as the installed app (the four signing secrets). Android refuses anything else, so a wrong or tampered file can never replace the app. A `…-TEMP-KEY.apk` cannot update a normally-signed app.
- The update only reaches phones that already run a build **with this feature**. A phone with an older build must install the new APK once by hand; after that, updates come through the app.
- Each phone needs the Android setting **Install unknown apps → allow for RM Store Attendance** (the app opens that screen the first time).
- The file is stored in Drive (folder `AppUpdates`) in 512 KB pieces; only signed-in users can download it. Uploading a newer version deletes the older file. The biggest APK accepted is 40 MB.

### Updating the project from a phone (no computer)
1. Upload an `update.zip` to the repo's main page (**Add file → Upload files**). The **Apply Update** workflow unpacks it into the project automatically and then **starts Build APK by itself** (no need to press anything).
2. When **Build APK** shows a green tick (about 5–10 minutes), download the new APK from **Releases**.

### Updating the GitHub Wiki from a phone
Upload a wiki zip (any name containing `wiki`, for example `RM-Store-wiki.zip`) to the repo's main page. The **Update Wiki** workflow (`.github/workflows/update-wiki.yml`) unpacks it, **replaces the wiki pages with exactly what is in the zip** (it must contain `Home.md`) and removes the zip. One-time setup: tick **Settings → Features → Wiki**, open the Wiki tab and save a first page by hand (GitHub creates the wiki storage only then), and add the workflow file on GitHub. Details: wiki page *Updating the Wiki*.

Workflow files (`.github/workflows/*.yml`) can't be changed by a zip — edit those directly on GitHub (paste the new `build.yml` over the old one). Backend changes (`Code.gs`) are not part of the zip either: paste them into Apps Script and deploy a **New version**.

---

## ⚙️ Configuration

| Setting | Where | Default |
|---|---|---|
| Sheet ID | `Code.gs` → `SHEET_ID` | your sheet |
| Timezone | `Code.gs` → `TZ` | `Asia/Dhaka` |
| Leave quotas | `Code.gs` → `QUOTA` | Casual 10, Sick 14, Earn 17, Paternity 3 (days/year) |
| Late cutoff | `Code.gs` → `LATE_HOUR`, `LATE_GRACE_MINUTES` | `9` and `10` (late from 9:10 AM) |
| Absent cutoff | `Code.gs` → `ABSENT_AFTER_HOUR` | `18` (6:00 PM) |
| Pay period | `Code.gs` → `PERIOD_START_DAY` / `PERIOD_END_DAY` | 26 → 25 |
| Weekly off | `Code.gs` → `isFridayDate` | Friday |
| Server URL | `Api.kt` → `Config.SCRIPT_URL` | — |
| Short sign-in length (box unticked) | `Code.gs` → `SHORT_SESSION_HOURS` | 12 hours |
| Minimum password length, lockout, token lifetime | `Code.gs` → `MIN_PASSWORD_LENGTH`, `MAX_FAILED_LOGINS`, `LOCKOUT_MINUTES`, `TOKEN_DAYS` | 8, 5 tries, 15 min, 30 days |
| Shortest reason for a leave request | `Code.gs` → `MIN_REASON_LENGTH` | 3 characters (a picture is also required) |
| Laptop model for managers only (set `false` to give it to everyone) | `Code.gs` → `LAPTOP_FOR_MANAGERS_ONLY` | `true` |
| Relations in the emergency-contact drop-down ("Other" is added by the app) | `Code.gs` → `RELATIONS` | Wife, Son, Brother, Mother, Father, Daughter |
| Night-hold hours | **Admin Panel → 📝 Entry** (the two constants `NIGHT_OPEN_HOUR` / `NIGHT_CLOSE_HOUR` in `Code.gs` are only the starting values) | `21` and `6` (9 PM to 6 AM) |
| Reminders on/off for everyone, and the first reminder time | `Code.gs` → `REMINDERS_ON`, `REMIND_FROM_HOUR`, `REMIND_FROM_MINUTE` | on, 8:30 AM (stops at `ABSENT_AFTER_HOUR`) |
| Biggest in-app update accepted | `Code.gs` → `UPDATE_MAX_BYTES` | 40 MB |
| Notice length of stay | `Code.gs` → `NOTICE_DEFAULT_DAYS`, `NOTICE_MAX_DAYS` | 7 days, 90 days |
| E-mail reports: people per request, most people in one go | `Code.gs` → `EMAIL_BATCH`, `EMAIL_MAX_RECIPIENTS` | 10, 300 |
| SMS starting monthly limit (change it in Admin Panel → 📱 SMS), longest message, most people per send | `Code.gs` → `SMS_DEFAULT_MONTHLY_LIMIT`, `SMS_MAX_LENGTH`, `SMS_MAX_RECIPIENTS` | 200, 300 characters, 200 people |
| Allowed check-in area | `Code.gs` → `GEO_CHECK`, `GEO_BOX` | Bangladesh + margin |
| Districts & thanas | `app/src/main/assets/bd_locations.txt` | 64 districts |

> **After any change to `Code.gs`:** saving isn't enough. Go to **Deploy → Manage deployments → ✏️ → Version: New version → Deploy**, or the live URL keeps running the old code.

The late/absent hours and pay period are also used by the app screens (Home and Calendar) — keep them in sync with `Code.gs` if you change them.

---

## 🧩 Troubleshooting

| Problem | Fix |
|---|---|
| *"Network or server error"* in the app | Check the phone's internet and that `Config.SCRIPT_URL` is the latest `/exec` URL. |
| App shows old behaviour after editing `Code.gs` | Deploy a **New version** (see above). |
| *"Conflicting app signatures"* when installing | Uninstall the old app once (see **Versions & updates**). |
| *"Too many wrong passwords"* | Wait the 15 minutes it shows; the pause ends by itself. |
| *"Your session ended"* | Normal after a password change on another phone, a logout, or 30 days of not opening the app. Sign in again. |
| APK is named `…-TEMP-KEY.apk` or won't update the installed app | The signing secrets are missing. Add the four secrets (see **Signing key**), uninstall the old app once, install the new one. |
| A leave request is refused with *"write the reason"* or *"attach a picture"* | Both are required: a reason of 3+ characters and a picture of the leave application. |
| *Take photo* does nothing on the Leave screen | Allow the Camera permission when Android asks (or in Settings → Apps), or use *Choose picture* instead. |
| The build is green but the *Publish release* step shows a red error | A pre-release with files was refused by GitHub (immutable releases). The APK is still in the run's **Artifacts**; the current `build.yml` saves such builds as a draft release instead. |
| No **Update** card on Home although I published a version | The published *build number* must be higher than the installed one (shown in Admin → App). Pull Home down to refresh it. |
| *"App not installed as package conflicts"* when updating | The new APK was signed with a different key (for example a `…-TEMP-KEY.apk`). Add the four signing secrets, rebuild, uninstall the app once, install, and publish updates from then on. |
| Update download fails or says *damaged* | Check the internet and tap Update again. If it keeps failing, upload the APK again from Admin → App. |
| Someone forgot their password | Admin Panel → Users → **Reset password** (this also lifts a *too many wrong passwords* pause). |
| The Admin Panel isn't in the 👤 menu | Their *Role* cell must say `admin`; then open or refresh Home once. |
| Someone lost their phone | In the Apps Script editor run `revokeOneUser` (edit the username inside it first), or `revokeEveryone`. |
| Build APK fails | Open the failed run's **Summary** — the *"Build failed – the real error"* box shows the exact line. The full log is saved as a `build-log` artifact. |
| Nothing is written to the Sheet | Make sure the script is bound to the Sheet or `SHEET_ID` is set, and that `setup` was run once. |

---

## 🔒 Security notes

- **Passwords** are stored only as salted PBKDF2-SHA256 hashes. The app never saves your password; it keeps a signed token (encrypted with the Android Keystore) that expires after 30 days, is renewed while you keep using the app, and stops working the moment you change your password, log out, or an admin revokes it.
- **Weak passwords are not allowed to stay.** A password that is too short or too easy (`1234`, `12345678`, `87654321`, the username, …) or a temporary one from an admin is accepted once at sign-in, but then the server refuses **everything** except *choosing a new password* (or signing out) until it is changed; the app shows a pop-up that cannot be dismissed. Run **`securityCheck`** from the Apps Script editor (Run, then View → Logs) to find and flag easy passwords across all accounts, spot a Manager loop, and list the admins.
- **Brute-force protection:** 5 wrong passwords pause that account for 15 minutes (counted under a lock, so a burst of parallel guesses can't slip through). Apps Script cannot see a caller's IP address, so this is per account, not per attacker; an attacker can pause someone's account, but cannot get into it.
- **Selfies, leave applications and locations are validated on the server:** photo required, JPEG only, size-limited; coordinates must be real and inside the allowed area; the phone's "fake GPS" flag is rejected. A phone with a modified app or a rooted device can still lie about its GPS — only Play Integrity attestation would close that, and it is not included.
- **Profiles** are stored on a **Profiles** tab (created automatically, one row per person, plain-text cells). A profile can be changed only by its owner **or an admin**; a profile photo is visible only to that person, the supervisors above them, and admins.
- **Spreadsheet formula injection** is blocked: text typed on a phone (address, hotel, leave reason) is always stored as text.
- **Admin actions** are checked on the server on every request (the *Role* cell is read each time, so removing `admin` takes effect at once), and adding a user or resetting a password needs the admin's own password again. A password an admin sets is temporary: the person must choose their own at first sign-in. Only someone who can edit the Sheet can create the first admin.
- **Authorization** is enforced on the server: selfies and leave-application pictures are visible only to the person and the supervisors above them; leave can be decided only by a supervisor above that person in the chain (never for yourself); team views only down your own chain.
- **App updates** are published only by an admin, who must confirm with their own password. Phones download them only when signed in, verify a SHA-256 fingerprint, and Android installs them only if they carry the same signing key as the installed app.
- **Manual entry** is checked on the server: a person can only fill a date an admin opened **for them**, once, not in the future, and the usual selfie / GPS / leave checks still apply.
- **Stronger admin protection (security update).** Six admin actions now need the admin's **own password**: changing who may use which menu, making a person active/inactive, posting a notice for everyone, removing a notice, and editing someone's profile or photo. After one correct password the server gives the app a short proof (15 minutes, kept only in memory, tied to that admin and that sign-in, and no other token can pass as it), so the next action does not ask again. Password hashing is now **150000 rounds** with an unpredictable salt (older hashes still work and upgrade themselves at the next sign-in; sign-in takes about a second). There are **no sample accounts**: `setup` creates none, and `makeAdmin` reads the Employee ID from the Script Property `MAKE_ADMIN_USER`.
- **E-mail alerts.** A forgot-password code works only for the address it was mailed to (if the profile e-mail changes, the code dies). Changing the e-mail on an account mails the **old** address (the new one is shown masked); when an **admin** changes it, that person is also signed out everywhere. Five wrong passwords mail the account owner and the admins **once** per pause (at most 10 such mails an hour, none when the daily mail quota is nearly gone), and the app as a whole hands out at most 20 reset codes an hour.
- **Optional extra locks (Script Properties).** `SHEET_ID` (move the sheet ID out of the code, then blank the constant in `Code.gs`), `APP_KEY` (the script refuses every request that does not carry `?k=<key>`; used together with the optional Cloudflare Worker `worker.js`) and `RELEASE_CERT_SHA256` (publishing an app is refused unless the APK is signed with your release key; the Executions log shows the fingerprint of the first APK you publish). The phone also deletes its **whole cache folder** at sign-out.
- **Temporary passwords** copied in the Admin Panel are marked sensitive on Android 13+ and wiped from the clipboard after one minute.
- **Backups are disabled** for the app, and saved data on the phone is encrypted and wiped at logout.
- The script URL is public (anyone who has the APK has it), so assume anyone can call it. Everything above is built with that in mind.
- **Protect the Google account** that owns the Sheet and the script: turn on **2-Step Verification** and share the Sheet only with people who must edit it. Whoever can edit the Sheet can read every profile and change any account, whatever the app does.
- Known limits: someone who knows a username can pause that account for 15 minutes by typing wrong passwords; a flood of requests to the public URL can slow everyone (Apps Script offers no per-caller limits); and a modified app or rooted phone can lie about GPS or send a stored photo. Managers reviewing selfies and locations is the practical control for the last one.
- Attendance time is stamped by Google's server. Leave balances reset each calendar year and count **Approved + Pending** requests. A manager is anyone who appears in another user's **Manager** column.
- Keep the repository **private** or limit who has write access: anyone who can push can change the app, and the *Apply Update* workflow applies uploaded zips automatically.

---

## 📁 Project layout

```
.github/workflows/
  build.yml            Builds the APK, numbers it, signs it, publishes it
  apply-update.yml     Unpacks an uploaded update.zip into the project
  update-wiki.yml      Unpacks an uploaded wiki zip and replaces the GitHub Wiki with it
app/
  build.gradle
  src/main/
    AndroidManifest.xml
    assets/bd_locations.txt     64 districts → thanas
    java/com/example/attendance/
      Api.kt            Server URL, session (token), API call helper
      Security.kt       Encrypted storage, wipe-on-logout, fake-GPS check
      Cache.kt          Saved-response cache for instant screens
      Admin.kt          Admin flag (the server decides who is an admin)
      AdminActivity.kt  Admin Panel: users, notices, holidays, publish app update
      NoticesActivity.kt  List of notices
      TargetActivity.kt   Target vs Collection screen
      SurveyActivity.kt   Surveys: list, answer form, your answers
      TeamMembersActivity.kt  Team members: a line manager's people with read-only Profile / Calendar
      SurveyAdminActivity.kt  Manage surveys: builder, results, Excel
      Attach.kt           Camera / gallery picture picker (survey attachments)
      Xlsx.kt             Small Excel (.xlsx) reader used for the target upload
      TargetImport.kt     Turns the two Excel files into data for the server
      SideMenu.kt         The 👤 side menu (search, menus with sub-menus, Light/Dark, logout)
      MenuLayout.kt       The admin-chosen order of the menus and Admin Panel tabs
      Theme.kt            Day / Night colours (Pal) and the App class that applies the saved choice
      Access.kt           Locked menus (Admin Panel > Users > Menu access)
      Sms.kt              Small helpers for the "also send as SMS" messages
      NoticeAlerts.kt   Bell count and the sign-in notice pop-up
      BellAnim.kt       The ringing bell + badge pop animation
      Photos.kt         Selfie / leave-application / profile picture loader and viewer
      ProfileActivity.kt  My Profile: photo, phones, email, emergency contact, bike, laptop
      AppUpdate.kt      In-app update: download, verify, install
      Ui.kt             Shared header + bottom bar
      MainActivity.kt           Home
      AttendanceActivity.kt     Selfie + location check-in
      LeaveActivity.kt          Leave + balances + application picture
      NightHoldActivity.kt      Night hold
      CalendarActivity.kt       Calendar
      TeamActivity.kt           Manager team view + approvals
      …                         Login, photos, pickers, dialogs
    res/                Layouts, colors, themes, drawables, xml/file_paths.xml (camera photo sharing)
```

---

## 📄 License

Personal / internal use project. Adjust and reuse as needed.
