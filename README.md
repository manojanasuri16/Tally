# Tally

**Your family's book, shared.** Track what your family owes and lends, and keep everyone in the family on the same page.

Tally is an Android app for family money: EMIs, credit cards, pledged gold, and money taken from or given to people you know. Each phone keeps the whole book and works offline; family members share it end to end encrypted.

**Get it:** [Tally on AppDrop](https://appdrop-store.vercel.app/apps/tally) (Android 10 or newer). It updates itself from there.

## What it does

- See what is still to pay, what is due this month (with the month's total) and what is overdue
- Bank EMIs (reducing or flat), interest only (every month, 3 months, 6 months or a year), no interest, or fixed instalments such as ₹1,000 a week
- Credit cards: outstanding, minimum due, pay-by date, statement and due days, a credit limit, and limits shared between cards
- A first payment date, so payments can fall on a different day from the day the money came
- Log each payment, with a bank SMS, photo or document if you like
- Share with family: say whose each entry is, and switch between **Everyone** and **Mine**
- Mark any entry private: it stays on your phone and is marked with a lock
- Deleted by mistake? An entry stays in **Recently deleted** for 30 days, and anyone in the family can bring it back
- Telugu, Hindi and English, with an Easy mode for bigger text and fewer choices
- Payment reminders and a home-screen widget

## Privacy

- No account, no sign-up, no ads, no analytics, no tracking.
- Everything shared with the family is end-to-end encrypted on the phone before it leaves. The small server that passes changes between phones (a Supabase project reached only through `tally_*` database functions) holds only ciphertext, and keeps files only until every member has them.
- A family's keys never reach the server. Joining needs an admin's approval on their phone; removing someone changes the keys.
- Private entries, their payments and their documents never leave the phone, except inside a backup file you make.
- An optional lock (a PIN, or the phone's own lock) seals the book on the phone, with a recovery code for backups.

How it works, and the threat model, are in [docs/DESIGN.md](docs/DESIGN.md). A security review and its fixes are in [docs/SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md).

## Building

Native Kotlin and Jetpack Compose, in `android/` (package `com.tally.book`, Android 10+).

```bash
export ANDROID_HOME=~/android-sdk
cd android
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/
```

A debug build needs the relay's address and publishable key as Gradle properties (`TALLY_SUPABASE_URL`, `TALLY_SUPABASE_ANON_KEY`, for example in `~/.gradle/gradle.properties`); without them the app works alone, without family sharing. Release builds also need the signing key (`TALLY_STORE_*`), which is never in this repository. The relay's SQL is in `supabase/migrations/`.

## Layout

```
android/     the app (core, model, data, sync, ui, widget) and its tests
supabase/    the relay: migrations and config
docs/        DESIGN.md (how it works) and SECURITY_REVIEW.md
appdrop/     the store listing: description, icon, screenshots
scripts/     release.sh
```

## License

Open source under the [MIT License](LICENSE). Contributions and issues are welcome.
