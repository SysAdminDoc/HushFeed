![Hushfeed. Take back your feed with focused controls for filtering, gestures, playback, downloads and privacy.](assets/readme-hero.png)

<p align="center">
  <a href="CHANGELOG.md"><img alt="version" src="https://img.shields.io/badge/version-0.79.0-6f42c1.svg" /></a>
  <a href="LICENSE"><img alt="license" src="https://img.shields.io/badge/license-GPLv3-blue.svg" /></a>
  <a href="https://www.android.com/"><img alt="platform" src="https://img.shields.io/badge/platform-Android-3ddc84.svg" /></a>
  <a href="https://github.com/MorpheApp/morphe-manager"><img alt="Morphe" src="https://img.shields.io/badge/works%20with-Morphe-00b894.svg" /></a>
  <a href="https://github.com/SysAdminDoc/hushfeed/discussions"><img alt="Discussions" src="https://img.shields.io/github/discussions/SysAdminDoc/hushfeed?color=0969da" /></a>
  <a href="https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/"><img alt="TikTok 47.5.3" src="https://img.shields.io/badge/TikTok-47.5.3-ff0050.svg" /></a>
</p>

<p align="center">
  <a href="https://ko-fi.com/X8K126YVER">
    <img height="42" src="https://storage.ko-fi.com/cdn/kofi2.png?v=3" alt="Buy me a coffee on Ko-fi" />
  </a>
</p>

<p align="center">
  <sub><em>If Hushfeed makes TikTok better for you, a coffee helps me keep testing patches and maintaining them as TikTok changes.</em></sub>
</p>

# Hushfeed

Hushfeed is a set of [Morphe](https://github.com/MorpheApp/morphe-manager) patches for TikTok. You bring the TikTok APK, Morphe Manager patches it on your phone, and the patched app gets a Hushfeed page in its settings with a switch for each patch you picked. Hide the ads and the clutter over videos. Make Follow and Like ask for a second tap. Save videos without the watermark, and stop the usage statistics and crash reports TikTok sends home.

Most switches start off, so a freshly patched TikTok looks and works the way it ships until you turn something on. What changed in each version is in the [changelog](CHANGELOG.md).

**[Add Hushfeed to Morphe](https://morphe.software/add-source?github=SysAdminDoc%2Fhushfeed)** | [Download the latest bundle](https://github.com/SysAdminDoc/hushfeed/releases/latest) | [Browse the 141 source patches](#patches)

> [!IMPORTANT]
> Hushfeed targets the global TikTok package, `com.zhiliaoapp.musically`, version [47.5.3](https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/tiktok-47-5-3-release/tiktok-47-5-3-2-android-apk-download/). Patch that exact build, as the bundle or the APK ([step 1](#install) says which). See [Supported target](#supported-target) for the build details.

Hushfeed v0.79.0 contains 141 patches for TikTok 47.5.3 and PineDrama 47.5.3. New in this one: PineDrama moves to 47.5.3, six more patches run on it, and imported fonts work there. TikTok gets a spam comment filter, screen recording for LIVEs and a blur behind the glass bar, and Hide videos you have already seen works on For You again. It needs Morphe Manager 2.0.0 or newer.

The main branch contains 141 patches, the same set as v0.79.0.

## What it changes

- **Feed:** Start with the Calm feed preset, or hide ads, Shop, LIVEs, stories, photo posts and any creator or video that matches your own rules. Your own posts are never hidden. TikTok can open on Following, Friends, Inbox or Profile instead of For You.
- **Touch controls:** Ask for a second tap before Follow, Like or a quick repost goes through. Remap or turn off long press and double tap, and keep For You where it is when you tap Home.
- **Playback:** Choose the speed and quality, stop loops, pick up where you left off or move on by itself. A video can keep playing after you leave the app.
- **Downloads:** Save videos without the watermark, full-size photos, the sound on its own and SRT subtitles, into folders and file names you choose. Saving works even when the creator turned downloads off.
- **Comments and inbox:** Filter comments by words or accounts and translate them into your language. Choose which Inbox rows show up.
- **Privacy:** Turn off supported telemetry, hide that you viewed a story or that you're typing, keep videos out of Watch history and put TikTok behind your fingerprint or PIN.
- **Screen time:** Set a daily budget in videos or minutes with a hold when it runs out, or lock the feed and keep TikTok for messages.

The settings screen comes in English, German, Spanish, Italian, Indonesian, Brazilian Portuguese, Russian, Turkish, Azerbaijani and Simplified Chinese. It follows the language TikTok runs in. Simplified Chinese is a first pass, so corrections are welcome.

The block, hide, sound and Not interested buttons Hushfeed can put on a video:

![Overlay controls](assets/overlay-controls.png)

## Install

1. Get the TikTok 47.5.3 bundle or APK from APKMirror, since Google Play only offers the newest build. For Morphe Manager on a phone, take the file marked **BUNDLE** (an `.apkm`) from the [47.5.3 release page](https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/tiktok-47-5-3-release/). Manager needs less memory for it, and on a device with 3 GB it ran out of memory on the APK but patched the bundle in about a minute. On a computer, take the [47.5.3 APK](https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/tiktok-47-5-3-release/tiktok-47-5-3-2-android-apk-download/) (arm64-v8a + armeabi-v7a, nodpi) instead, since the desktop tool's merge of a bundle breaks three patches ([why](#why-you-have-to-fetch-that-apk-yourself)). If you already have the APK, Manager can still try it. Get the bundle if it runs out of memory.

   On Android 11 and newer, Morphe Manager checks that TikTok's own key signed the file and warns you if it didn't. That warning means the file was changed after TikTok published it, so download it again.
2. Use Morphe Manager 2.0.0 or newer. Hushfeed bundles after v0.77.0 are built against patcher 2.0.0, which Manager 2.0.0 was the first to ship, and older Managers won't load them. morphe-desktop 1.19.0 or newer works from a computer. Give it 2 GB of memory (`java -Xmx2g -jar morphe-desktop-1.19.0-all.jar patch ...`), because with every patch picked it runs out at 1 GB, which is Java's default on a computer with 4 GB of RAM. Universal ReVanced Manager can't import a Morphe bundle at all ([its issue #733](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/733)).
3. Add Hushfeed as a source. On the phone, open [Add Hushfeed to Morphe](https://morphe.software/add-source?github=SysAdminDoc%2Fhushfeed). If **Open in Morphe** leaves you in the browser, open Morphe Manager, tap **Sources**, tap **+**, and paste `https://github.com/SysAdminDoc/hushfeed`. You can also download the `.mpp` file from the [latest release](https://github.com/SysAdminDoc/hushfeed/releases/latest) and load it as a local bundle. It's a patch bundle, not a TikTok APK.
4. Pick the patches. Simple mode in Morphe Manager selects 122 of the 141 Hushfeed patches. The other 19 need Expert mode in Manager settings, and [Patches](#patches) says why each one stays out. Manager 2.0.0 shows all 141 Hushfeed patches in eleven categories you can expand or collapse. Leave **Use embedded VM** on under **Settings > Patcher**, since it lets the patcher use whatever memory the phone can spare.
5. Patch the original file from step 1. Hushfeed stops with an error on an APK it has already patched. Keep the manager's existing signing key, because an update only installs over your copy, with your login intact, when it's signed with the same key.
6. Install the patched APK. If TikTok from Google Play is still on the phone, uninstall it first. Android won't put the patched app over it because they're signed with different keys. Uninstalling erases TikTok's data on the phone, so you'll log in again, and drafts you haven't posted go with it. Then open TikTok and go to **Settings and privacy**. Hushfeed is the first row, and [Settings](#settings) has two quicker ways in.

The patches that take files out of TikTok do it while patching, so Pause and a settings import can't put those files back. Read the [native-language recovery steps](#tiktok-stays-in-english-after-removing-language-packs) before you remove languages.

## Settings

Besides the row at the top of TikTok's **Settings and privacy**, two shortcuts skip Profile and work while you're signed out: press and hold **For You** at the top of the feed, or long-press **Home** on the bottom bar. A quick tap on either still does what it always did. The Home shortcut needs **Feed tab navigation** selected along with **Settings** when you patch, and it's the one to use if you've hidden the tab names at the top of the feed.

The home page has a search box, which finds any row by its title or description and flips a plain switch right in the results. Under it, Quick setups turns on Quiet or Privacy, or puts every switch back to its default, in one tap with Undo. Every settings page sits right on the home page under a small heading, so nothing is more than one tap away:

| Heading | Pages |
| --- | --- |
| Your feed | Feed filter, Feed tabs, Feed screen, Playback and Screen time |
| Conversations | Comments and Inbox |
| Saving and sharing | Downloads and Share sheet |
| Privacy and app | Privacy, App, Region, Backup and restore, and Feature Gate Lab when it's installed |
| Hushfeed | Status, Pause Hushfeed, Diagnostics, About Hushfeed, Report a problem and Support Hushfeed |

The status chip next to the Hushfeed title scrolls down to the full status card. About Hushfeed has the build details and the license notices. **Report a problem** opens a GitHub bug report with your versions and the switches you have on filled in, and **Support Hushfeed** opens Ko-fi. A few pages you'll want to know about:

- **Pause Hushfeed**, under the Hushfeed heading, turns the runtime features off from the next start and keeps every choice you made. [Is it Hushfeed?](#is-it-hushfeed-pause-it-and-see) lists what it can't reach.
- **Backup and restore** saves your settings and Feature Gate Lab rules to a JSON file. A backup from an older Hushfeed restores what it has and leaves the rest as you set it. Restore and reset keep one undo copy inside TikTok, but clearing TikTok's data takes that copy too, so back up to a file first.
- **Import watch history**, under Feed filter > Seen videos, reads the JSON file from [your TikTok data download](https://support.tiktok.com/en/account-and-privacy/personalized-ads-and-data/requesting-your-data) so Hide already seen videos knows what you've watched. The file's dates don't name a time zone, so they're read in your phone's zone when you pick the file. It takes up to 2 MB and 10,000 entries.
- **Diagnostics** exports a report you can attach to a bug, and the report stays on your phone until you share it. Its **Hook status** row says whether the hooks found what they need in your TikTok build. It hears from comments, comment translation, the inbox, the share sheet, the feed overlay, the bottom navigation and the story viewer, feed models, playback quality, sensitive warnings, CAPTCHA account state, the external browser, sticker saves and story saves. A switch can read on while its hook found nothing, and this row is how you'd know.
- **Feature Gate Lab** changes values TikTok reads for its own features. Its reviewed presets show each value before you apply it, and Undo last Lab change puts the old ones back. A gate outside those presets can break sign-in or the feed, so leave it alone unless you know what it does.

Settings use the same rounded panels and controls across every page, in dark and light mode. The [page mockups and implementation notes](concepts/patch-development.md#settings-design-concepts) show the design behind them.

<img src="assets/settings/settings.png" alt="Hushfeed settings home" width="260" /> <img src="assets/settings/playback-sound.png" alt="The Player card on the Playback page" width="260" /> <img src="assets/settings/playback-sound-light.png" alt="The Player card on the Playback page in light mode" width="260" />

<details>
<summary>Every settings page</summary>

| Page | Screenshot |
|---|---|
| Feed filter | [View](assets/settings/feed_filter.png) |
| Local creator list | [View](assets/settings/creator-list.png) |
| Feed tabs | [View](assets/settings/feed_navigation.png) |
| Feed screen | [View](assets/settings/interface.png) |
| Playback | [View](assets/settings/playback.png) |
| Playback sound | [Dark](assets/settings/playback-sound.png), [light](assets/settings/playback-sound-light.png) |
| Screen time | [View](assets/settings/screen_time.png) |
| Comments | [View](assets/settings/comments.png) |
| Downloads | [View](assets/settings/downloads.png) |
| Share sheet | [View](assets/settings/share.png) |
| Inbox | [View](assets/settings/inbox.png) |
| Privacy | [View](assets/settings/privacy.png) |
| Region | [View](assets/settings/region.png) |
| App | [View](assets/settings/behavior.png) |
| Diagnostics | [View](assets/settings/diagnostics.png) |
| Backup and restore | [View](assets/settings/backup.png) |
| Settings search | [View](assets/settings/search.png) |
| Settings recovery | [View](assets/settings/settings-error.png) |
| What's new | [Dark](assets/settings/whats-new.png), [light](assets/settings/whats-new-light.png) |
| Feature Gate Lab | [View](assets/settings/lab.png) |
| Twice the text size | [View](assets/settings/two-times-text.png) |
| Mirrored layout at twice the text size | [View](assets/settings/rtl-large.png) |

</details>

## Patches

Simple mode in Morphe Manager selects 122 of these patches. Each row says what the patch does, whether its switch starts on or off, and where to find it. The other 19 stay out of the default selection, and you pick them in Expert mode:

- Change app name, Custom launcher icon, Hide Play Store update offer, Look like the store app and Run beside the store app change the app's name, icon, signature, package or version code.
- Accent color, AMOLED dark theme, Block P2P video relay, Remove content credential and card scanner assets, Remove creation tools, Remove LIVE extras, Remove unused language packs and Remove voice tools change TikTok's files while patching. Only a fresh patch takes that back.
- Trust user certificates changes TikTok with no switch in front of it.
- Feature Gate Recorder and Network request report only record what TikTok does, for research. Hide the risk control CAPTCHA sits on TikTok's account checks.
- Region spoof changes the region TikTok sees on every read, sign-in included. Skip first-launch setup only helps when it's picked before TikTok's first start, so its switch starts on.

| Patch | Description |
|---|---|
| `Accent color` | Swaps the red of TikTok's main buttons and red text for blue, green, purple, orange, teal or a color code you type in this patch's options, or for the color of the phone's wallpaper theme on Android 12 and up. Unread dots and counts change with it. Warnings stay red, and so can icons with the red drawn in. It's built in while patching, so only patching again without it undoes it. |
| `Advanced downloads` | Adds more ways to save: pick the video quality, save full-size photos, the sound on its own or a photo post as one video, hold a profile picture or story to save it, save a story or a video's cover from the share sheet, and pick videos on a profile grid to save in one run. Starts off. Turn it on in Hushfeed settings > Downloads. |
| `Allow Duet and Stitch` | Shows the Duet and Stitch buttons on videos whose creator turned them off, so you can still make one, and Add to Story in the share sheet and on stories when the creator turned off content reuse. TikTok may still refuse some videos or the upload. Starts off. Turn it on in Hushfeed settings > Share sheet. |
| `Allow photo comments` | Lets PineDrama's comment box ask TikTok's server about photo comments, like TikTok does. TikTok's server still decides whether your account can post one. Starts off. Turn it on in Hushfeed settings > Comments. |
| `Allow screenshots and Circle to Search` | Lets screenshots, screen recordings and Circle to Search work on TikTok screens that would otherwise come out black. Starts off. Turn it on in Hushfeed settings > App, then restart TikTok. |
| `Always show publish date` | Shows the date a video was posted next to the creator's name, so you can tell how old it is. It can also show the exact time, dates and lengths on profile grids, or sort the videos a grid has loaded. On by default. Turn it off in Hushfeed settings > Feed screen. |
| `Always upload in HD` | Posts every video you upload in HD, as if you'd turned on TikTok's own HD upload setting each time. Videos TikTok doesn't count as high quality post as usual. Starts off. Turn it on in Hushfeed settings > App. |
| `AMOLED dark theme` | Makes TikTok's dark mode backgrounds pure black, or a dark color you pick in this patch's options. Black lets OLED screens switch those pixels off. Light mode doesn't change. It's built in while patching, so only patching again without it undoes it. |
| `App lock` | Asks for your fingerprint, face or PIN before TikTok opens, and again when you come back after a time you pick, so nobody else can open it on your phone. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Automatic video advance` | Moves on to the next video by itself when one ends, so you can watch hands-free. It can also turn on TikTok's auto scroll in search results. Starts off. Turn it on in Hushfeed settings > Playback. |
| `Block author button` | Adds buttons on videos to block the creator, hide them only on your phone, or block the sound, so you can get rid of what you don't want in one tap. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Block contact list access` | Gives TikTok an empty contact list when it tries to read your phone's contacts, so it can't use them to suggest friends. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Block installed app scanning` | Gives TikTok an empty list when it asks which apps are on your phone, so it learns less about you. Opening another app from TikTok still works. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Block P2P video relay` | Removes the files TikTok uses to pass videos on to other viewers through your phone's internet connection. The app gets about 3.5 MB smaller. It's built in while patching, so only patching again without it brings them back. |
| `Block popups` | Lets you stop TikTok's popups, like friend suggestions and offers. Each one joins a checklist after it first appears, and once ticked it won't come back. Sign-in, age and verification screens are never blocked. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Block suggested video notifications` | Stops TikTok's "Videos you might like" notifications about popular videos it picked for you. Messages, comments, likes and posts from people you follow still come through. On by default. Turn it off in Hushfeed settings > Inbox. |
| `Camera and microphone indicator` | Shows a small mark in the top corner while TikTok is using the camera or microphone, so you always know when it is. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Change app name` | Shows a name you choose under the app icon, so the patched TikTok is easy to tell apart from another copy. Type the name in this patch's options. Inside the app it still says TikTok. |
| `Comment publish diagnostics` | Adds a note to Hushfeed's diagnostic report about how far a comment got when it fails to post, which helps track down comments that vanish without an error. Works as soon as you patch it in, with no switch. |
| `Comment send fix` | Fixes comments that TikTok sometimes drops without any message, so the comment you send actually posts. Works as soon as you patch it in, with no switch. |
| `Comment sort controls` | Shows TikTok's full comment sort menu on every video, with hot, newest, media and creator options, even when your account only got a cut-down version. Starts off. Turn it on in Hushfeed settings > Comments. |
| `Comment tools` | Hides comments with words you pick or from accounts you pick, and adds a search box for a video's comments. It can also make links tappable and show poll results before you vote. Starts off. Turn it on in Hushfeed settings > Comments. |
| `Confirm feed interactions` | Asks for a second tap before Follow, a like, or a quick repost goes through, so a stray tap doesn't count. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Copy bio and IDs` | Lets you long-press a bio to copy it, and adds share sheet buttons that copy a username, user ID or video ID. On by default. Turn it off in Hushfeed settings > App. |
| `Copy comments without username` | When you copy a comment, you get just its text, without the username in front. On by default. Turn it off in Hushfeed settings > Comments. |
| `Custom launcher icon` | Gives TikTok's home screen icon a themed version that takes your wallpaper's color on Android 13 and up. The options can also make it black, plain or your own picture. Only patching again without it puts the old icon back. |
| `Custom offline videos limit` | Lets you pick how many videos TikTok saves for watching offline, from 1 to 10,000, and keep them until you delete them. Handy when you're often without internet. Starts off. Turn it on in Hushfeed settings > Downloads. |
| `Device privacy guard` | Stops TikTok reading what you copied and gives it a blank advertising ID. Can also hide a VPN connection and root status with details about root tools, and give TikTok a new device identity. Each switch starts off in Hushfeed settings > Privacy. Changing Hide root needs a restart. |
| `Diagnostic tools` | Adds logs and reports you can save or share when something goes wrong, so a problem is easier to report and fix. Starts off. Turn it on in Hushfeed settings > Diagnostics. |
| `Disable login requirement` | Stops TikTok from forcing you to sign in before you can keep browsing. Works as soon as you patch it in, with no switch. |
| `Disable screen capture detection` | Stops TikTok from noticing and reacting when you take a screenshot or record the screen. Works as soon as you patch it in, with no switch. |
| `Disable telemetry` | Stops TikTok sending usage statistics and crash reports to TikTok and the tracking services it uses, so less of what you do in the app leaves your phone. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Disable the long press quick share` | Stops a long press on Share from opening TikTok's quick share, so you don't send a video by accident. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Disable the long press repost` | Stops a long press on Like from opening TikTok's repost option, so you don't repost by accident. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Double-tap controls` | Lets a double tap on a video do nothing, open its comments, copy the link, save the sound or the frame on screen, speed the video up, or set a sleep timer instead of liking it, so you don't like videos by accident. Each corner of the video can get its own action too. Starts off. Pick an action in Hushfeed settings > Feed screen. |
| `Downloads` | Saves videos and photos without TikTok's watermark, even when the creator turned downloads off. You can also choose the folders and file names. On by default. Turn it off in Hushfeed settings > Downloads. |
| `Drop the animated image cache` | Lets you make TikTok decode animated stickers and GIFs one frame at a time as they play, instead of a few frames ahead, so they use a little less memory. Starts off. Turn it on in Hushfeed settings > App. |
| `Enable voice comments` | Lets you turn on TikTok's voice comments, so you can record and post a spoken comment, for accounts that don't have them yet. It hasn't been tried on a real account, so it may not work. Starts off. Turn it on in Hushfeed settings > Comments. |
| `Expand activity list` | Shows the whole Activity and New followers lists in the Inbox, so you don't have to tap View all. Starts off. Turn it on in Hushfeed settings > Inbox. |
| `Feature Gate Lab` | Adds an advanced page that lists the hidden settings TikTok uses to test features and lets you change them. It's for people who like to experiment. Starts off. Turn it on in Hushfeed settings > Diagnostics. |
| `Feature Gate Recorder` | Records which hidden TikTok settings the app reads while you use it, and which ones changed. It's a research tool and changes nothing in TikTok. Start a recording in Hushfeed settings > Diagnostics. |
| `Feed filter` | Hides ads in the feed, and lets you hide other things you don't want to see, like LIVEs, Shop posts, AI videos or videos with words, creators or sounds you pick. Hiding ads is on by default. Everything else starts off in Hushfeed settings > Feed filter. |
| `Feed tab navigation` | Lets you hide feed and bottom tabs you don't use, rename the bottom tabs, choose the tab TikTok opens on, stop For You reloading when you tap Home and hold Profile to open your Watch history. Starts off, except holding Home to open Hushfeed settings. Find it in Hushfeed settings > Feed tabs. |
| `Feed text sizes` | Lets you make video descriptions, creator names and post dates bigger or smaller, so they're easier to read. Starts off. Pick a size in Hushfeed settings > Feed screen. |
| `Fit the video to the screen` | Shows the whole video instead of cutting off its sides on folding phones, squarer screens and split screen, and fits photo slideshows whole on squarer screens. A second switch does the opposite and fills the screen. Starts off. Turn it on in Hushfeed settings > Playback. |
| `Fix Google login` | Makes Sign in with Google work in the patched app, where it would otherwise fail. Works as soon as you patch it in, with no switch. |
| `Foldable split comment view` | On a folding phone or tablet, shows comments beside the video instead of over it when the screen is wide enough. Starts off. Turn it on in Hushfeed settings > App. |
| `Follow diagnostics` | Tells you when TikTok quietly turned down a follow, and why, since the app shows it as if it worked. With diagnostic logging on, the details go in the report too. Works as soon as you patch it in, with no switch. |
| `Ghost mode` | Stops TikTok telling people you viewed their story or profile, or that you're typing. What TikTok already recorded stays. A separate switch hides your online status. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Hide already seen videos` | Remembers the videos you've watched and hides them when the feed sends them again, so you only see new ones. The list stays on your phone. Starts off. Turn it on in Hushfeed settings > Feed filter. |
| `Hide CAPTCHA popups` | Skips the puzzle TikTok shows while you're just browsing. A puzzle right after you like, follow, comment or repost still shows, and so do sign-in, SMS and two-step checks. Starts off. Turn it on in Hushfeed settings > Feed screen, then restart TikTok. |
| `Hide comment popup ads` | Stops the brand animation that pops up over the comments when someone types a word or emoji an advertiser paid for. Starts off. Turn it on in Hushfeed settings > Comments. |
| `Hide feed follow button` | Removes the + follow button under creators' pictures on the feed, so you don't follow someone by accident. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Hide feed LIVE button` | Removes the LIVE button and the side menu button from the top left of the feed, for a cleaner screen, and can stop a right swipe on For You from opening the side menu. Each has its own switch, and all start off. Turn them on in Hushfeed settings > Feed screen. |
| `Hide feed save button` | Removes the save button from the right side of the feed, for a cleaner screen. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Hide feed search button` | Removes the search button from the top right of the feed, for a cleaner screen. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Hide floating promotions` | Hides the floating promotion badges, coins and timers on the feed, and can hide the rewards button on your profile. Starts off. Turn it on in Hushfeed settings > Feed screen, and App for the rewards button. |
| `Hide inbox items` | Lets you hide Inbox rows you don't use, like message requests, TikTok Shop and the stories row, plus call buttons and suggested replies in chats. Each has its own switch. Also offers search in loaded Activity notifications. Starts off. Turn it on in Hushfeed settings > Inbox. |
| `Hide inbox stories` | Hides the row of stories at the top of the Inbox. It uses the same switch as Hide inbox items. Starts off. Turn it on in Hushfeed settings > Inbox. |
| `Hide comment typing suggestions` | Hides the emoji and sticker suggestions that pop up above the comment box while you type. The emoji and sticker buttons still work. Starts off. Turn it on in Hushfeed settings > Comments. |
| `Hide profile shortcuts` | Hides the shortcuts you pick from the row under a profile's bio, like TikTok Studio or Your orders, and can hide the Thoughts bubble, and Balance and the TikTok Minis icons in your profile's menu. It can also turn Thoughts on for accounts TikTok left out. Starts off. Turn it on in Hushfeed settings > App, then restart TikTok. |
| `Hide search suggestions` | Hides the searches TikTok suggests before you type, and can hide the search rewards banner. Your own search history stays. Starts off. Turn it on in Hushfeed settings > App. |
| `Hide suggested accounts` | Hides TikTok's People you may like suggestions in the Inbox, on profiles, on the Friends tab and in the feed. It uses the same switch as Hide inbox items. Starts off. Turn it on in Hushfeed settings > Inbox. |
| `Hide the launcher shortcuts` | Empties the menu that pops up when you press and hold TikTok's icon on your home screen. Tapping the icon still opens the app. Starts off. Turn it on in Hushfeed settings > App. |
| `Hide Play Store update offer` | Stops the Play Store offering to swap the patched TikTok for the store version. The catch: a build with a lower version number won't install over it, and uninstalling to go back can erase TikTok's data on your phone. |
| `Hide the risk control CAPTCHA` | Lets Hide CAPTCHA popups also skip the puzzle TikTok's account safety check raises while you're just browsing. SMS and two-step checks always show. It sits on TikTok's account checks, which is why it isn't picked for you. |
| `Hide video overlays` | Lets you hide clutter over videos, like the caption, the music line, buttons on the right, surveys and location labels, so you see more of the video. It can also make the music disc spin again or keep it still. Each has its own switch. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Hold-and-slide 2x lock` | Turns on TikTok's own gesture where you hold a video, slide down and let go to keep it playing fast without holding. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `In-app browser privacy guard` | Stops websites you open in TikTok's built-in browser from reaching into the TikTok app. TikTok's own pages, like Watch history and checkout, keep working. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Keep a streak going` | Sends a daily message you choose to people you pick, at a time you set, so your message streaks keep going on days you don't open TikTok. Starts off. Turn it on in Hushfeed settings > Inbox. |
| `Keep playing in the background` | Keeps the video playing when you leave TikTok or turn the screen off, with a notification to pause it. It can also start the video over when it ends. Handy for music and talks. Starts off. Turn it on in Hushfeed settings > Playback, then restart TikTok. |
| `Keep pulled sounds` | Plays the sound on videos TikTok muted for copyright or because of where you live. TikTok may still say the sound isn't available. Starts off. Turn it on in Hushfeed settings > Playback. |
| `Keep the app language` | Lets you keep the language you picked in TikTok's own settings. When TikTok starts and decides the phone's language changed, it drops that pick and follows the phone, which can leave it in the wrong language after a reboot. Starts off. Turn it on in Hushfeed settings > App. |
| `Keep the Favorites tab` | Keeps the Favorites tab and your saved videos on your profile when TikTok tries out a version of the app that hides them, and makes the collections popup you get by holding the Favorites button dark when TikTok is. Both are on by default. Turn them off in Hushfeed settings > App. |
| `Keep the screen's brightness in the camera` | Stops TikTok from turning your screen brighter while you film, edit or post a video. Starts off. Turn it on in Hushfeed settings > App. |
| `Report a newer phone to effects` | Tells TikTok's effects server you have a newer phone, so effects it turns down on older phones get another chance. Starts off. Turn it on in Hushfeed settings > App. |
| `Keep the screen's refresh rate` | Stops TikTok slowing your screen down to the video's frame rate, so scrolling stays smooth on 90 or 120 Hz phones. Starts off. Turn it on in Hushfeed settings > App. |
| `Lift text length limits` | Lets you write longer comments, repost notes and bios than the app normally allows. TikTok can still refuse one that's too long. Starts off. Turn it on in Hushfeed settings > Comments. |
| `Limit background traffic` | Lets you stop TikTok loading upcoming videos ahead of time, which uses less data in the background, but videos may take a moment longer to start. Starts off. Turn it on in Hushfeed settings > App. |
| `LIVE controls` | Stops a LIVE in the feed from opening by itself after a countdown. It can show a LIVE's exact viewer count instead of a rounded one, offer a quality choice and clear screen in more LIVE rooms, and keep your quality pick after TikTok restarts. Starts off. Turn it on in Hushfeed settings > Playback. It also keeps a LIVE in the feed from showing the Service+ row of the LIVE before it, and can add a Record button to a LIVE room that saves what you watch from when you tap it. |
| `Load all saved stickers` | Loads every sticker you saved when you open the sticker panel. PineDrama otherwise shows only the first 100 and never loads the rest. Works as soon as you patch it in, with no switch. |
| `Location access governor` | Gives TikTok no location when it asks, so it can't see where you are from your phone's location services. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Long-press controls` | Lets a long press on a video like it, open the comments, copy the link, save the sound or the frame on screen, or set a sleep timer, and can give a creator's videos For You's full long-press menu. Starts off. Pick an action in Hushfeed settings > Feed screen. |
| `Look like the store app` | Makes the patched app answer TikTok's checks as if it came from the Play Store, which can help when follows or likes undo themselves. TikTok has other checks, so it may not be enough. On once picked. Turn it off in Hushfeed settings > App. |
| `Mute feed videos` | Mutes feed videos without changing your phone's volume, so music from another app keeps playing. You can also add a mute button on videos. Starts off. Turn it on in Hushfeed settings > Playback. |
| `Network proxy` | Sends TikTok's feed, search and comments through a proxy server you set up. Videos and LIVEs still load directly, and so do Hushfeed's own saves and short-link lookups. Other apps aren't affected. Starts off. Turn it on in Hushfeed settings > Region, then restart TikTok. |
| `Network request report` | Counts the requests TikTok sends to its servers and adds the totals to the diagnostic report. It changes nothing in TikTok, but it has no switch and most people don't need it. |
| `Not interested button` | Adds a button on videos that tells TikTok you're not interested in one tap, so it can show you fewer like it. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Notification controls` | Lets you turn off new follower notifications and streak reminders, which TikTok has no switch for, or turn off TikTok's notifications altogether. Starts off. Turn it on in Hushfeed settings > Inbox. |
| `Open external links directly` | Opens website links from profiles and stories in your phone's own browser instead of TikTok's built-in one. On by default. Turn it off in Hushfeed settings > Privacy. |
| `Open the comments at full height` | Opens the comment sheet at the full height of the screen instead of part way up. Starts off. Turn it on in Hushfeed settings > Comments. |
| `Picture-in-picture` | Keeps the video playing in a small window when you leave TikTok, so you can keep watching while you use other apps. Starts off. Turn it on in Hushfeed settings > Playback. |
| `Play SDR instead of HDR` | Plays the normal version of HDR videos, so your screen doesn't suddenly jump to full brightness. Starts off. Turn it on in Hushfeed settings > Playback. |
| `Playback quality` | Lets you choose the video quality, like highest, lowest or 720p, and set a lower quality for mobile data to use less data. Starts off. Pick a quality in Hushfeed settings > Playback. |
| `Playback speed` | Remembers the speed you picked for the next video, and lets you set a default speed, add speeds up to 3x to the menu and choose the speed for press and hold. On by default. Turn it off in Hushfeed settings > Playback. |
| `Prefer H.264 playback` | Plays videos in an older format your phone decodes more easily, which can help older phones play smoothly and run cooler. Videos without it play as before. Starts off. Turn it on in Hushfeed settings > Playback. |
| `Profile background` | Opens TikTok's own profile background editor in Edit profile on accounts TikTok hasn't offered it to yet, and shows the backgrounds other profiles have set. TikTok's servers may still refuse to save one for such an account. Starts off. Turn it on in Hushfeed settings > App. |
| `Region spoof` | Makes TikTok's region and time zone match the country you set for SIM spoof. It changes every region check, sign-in included, and its store region switch is experimental, so it isn't picked for you. Starts off. Turn it on in Hushfeed settings > Region. |
| `Remember clear display` | Keeps clear display, TikTok's mode that hides the buttons over a video, on as you swipe to the next video (`Keep Clear display across videos` turns that part off). It can also turn on by itself after a delay you pick, which starts off in Hushfeed settings > Feed screen. When clear display ends, the Following stories bubble TikTok hid comes back too. |
| `Remove avatar rings` | Takes the story and LIVE rings off profile pictures, so tapping a picture opens the profile instead of a story or LIVE. It can also open a LIVE creator's Story from their profile picture or their chat in the Inbox. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Remove content credential and card scanner assets` | Removes TikTok's payment card scanner and content credential files, saving about 11 MB of storage. Anything in TikTok that needs them may stop working. Only patching again without it brings them back. |
| `Remove creation tools` | Removes TikTok's camera, editing and effects files, saving about 38 MB of storage. The catch: the Create tab and every recording, editing and effects tool stop working, and opening one, like effect search, can close TikTok. Leave it out if you make videos. |
| `Remove LIVE extras` | Removes the files for LIVE co-hosting, joining a LIVE as a guest, matches, games and gift effects, so the app gets about 3 MB smaller. The catch: in LIVEs, battle scores and guest names can go missing, and joining someone's LIVE by voice or video, co-hosting, games or animated gifts may stop working. |
| `Remove unused language packs` | Removes the app languages you don't list in this patch's options, saving up to about 26 MB of storage. English is always kept. With no list it keeps them all. Only patching again brings removed ones back. |
| `Remove voice tools` | Removes TikTok's speech recognition files, saving about 16 MB of storage. The catch: voice search, naming a song by listening, talking to TikTok's AI assistant and TikTok's music check on videos you post all stop working. Only patching again without it brings them back. |
| `Repost diagnostics` | With diagnostic logging on, notes when you repost a video and what TikTok answered, to help track down reposts that don't stick. Works as soon as you patch it in, with no switch. |
| `Resource and battery governor` | Stops TikTok reading your phone's motion sensors, which it can use to identify your phone, and stops a background speed test that can use a lot of memory. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Resume videos after scrolling` | When you scroll back to a video, it picks up where you left off instead of starting over. On by default. Turn it off in Hushfeed settings > Playback. |
| `Run beside the store app` | Lets a renamed copy of TikTok, made with Morphe's Clone app patch, sign in while the regular TikTok stays installed. Pick it together with Clone app. Google and Facebook sign-in won't work in the copy, so use email or phone. |
| `Sanitize sharing links` | Removes the tracking codes from TikTok links you share, so the link carries less about you. It can also swap tiktok.com for another site. On by default. Turn it off in Hushfeed settings > Privacy. |
| `Settings` | Adds the Hushfeed settings page to TikTok, at the top of Settings and privacy. Pressing and holding For You at the top of the feed opens it too. That's where you turn Hushfeed's features on and off. Works as soon as you patch it in, with no switch. |
| `Share sheet tools` | Lets you hide people, apps and buttons you don't use from the share sheet, add apps you do use, and copy a video's link with one press on Share. Starts off. Turn it on in Hushfeed settings > Share sheet. |
| `Show author region` | Shows the country a video was posted from next to the creator's name. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Show commenter handle and region` | Shows a commenter's @username in place of their display name and the country their account is set to after the comment's time. It can also give each commenter's name a color of its own. Each starts off. Turn them on in Hushfeed settings > Comments. |
| `Show engagement rate` | Shows how many people liked, commented on, shared or saved a video compared with how many watched it, next to the creator's name. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Show exact counts` | Shows likes, comments and other counts as full numbers, like 1,234,567 instead of 1.2M. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Show follow status` | Shows on a profile whether that person follows you back, and marks accounts in your follow lists that don't. On by default. Turn it off in Hushfeed settings > App. Optional local counts and appearance controls apply to your own profile. |
| `Show LIVE search` | Adds TikTok's search button inside the LIVE section where TikTok supports it, so you can look for LIVEs. Starts off. Turn it on in Hushfeed settings > App. |
| `Show the progress bar` | Shows the progress bar on videos where TikTok hides it, so you can see how long a video is and skip around. On by default. Turn it off in Hushfeed settings > Playback, where you can also show the time beside the bar or give it a bigger handle. Seek bars for video stories and sound previews are there too. |
| `Show the progress bar thumbnail` | Shows a small preview picture while you drag the progress bar, so you can find the part you want. On by default. Turn it off in Hushfeed settings > Playback. |
| `SIM spoof` | Makes TikTok see the SIM country and carrier you choose. It may not change your region, since TikTok also goes by your internet connection and account. Starts off. Turn it on in Hushfeed settings > Region. |
| `Skip content warnings` | Plays videos without the warning screen you'd have to tap through first, and can hide the Check sources and Check your sources banners. Starts off. Turn it on in Hushfeed settings > Feed screen. |
| `Skip first-launch setup` | Skips TikTok's setup screens on a fresh install, like the interest picker and the swipe tutorial. Sign-in and age screens still show. Its switch is on once picked, since setup runs before you can reach settings. Turn it off in Hushfeed settings > App. |
| `Skip passkey sign-in` | Lets you sign in another way, like your password or a code by email or text, when TikTok wants a passkey. Password managers won't hand a passkey to a patched app, so that step can't finish here. With the switch on, TikTok treats your phone as one without passkeys. Starts off. Turn it on in Hushfeed settings > App. |
| `Skip the splash ad` | Lets you stop the full-screen ad TikTok can show while it starts up. Starts off. Turn it on in Hushfeed settings > App. |
| `Skip update checks` | Lets you stop two background tasks TikTok uses to check for updates, one of them when your phone starts. Some in-app update prompts may stop. Play Store updates still work. Starts off. Turn it on in Hushfeed settings > App. |
| `Stay on the video in full screen` | When a video ends in TikTok's full screen view, it stays on that video instead of moving to the next one. Swiping still moves on. Starts off. Turn it on in Hushfeed settings > Playback. |
| `Stop on-device AI profiling` | Lets you stop TikTok's built-in AI engine from starting, so it doesn't get a copy of everything TikTok logs about your use. TikTok works as if the engine weren't there. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Stop recording watch history` | Stops TikTok adding the videos you watch to your Watch history. The catch: your views stop counting, and For You learns less about what you like. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Stop saving search history` | Stops TikTok saving your new searches on your phone. Older searches stay until you delete them, and TikTok's servers may keep their own record. Starts off. Turn it on in Hushfeed settings > Privacy. |
| `Stop search autoplay` | Stops videos in search results from playing by themselves. Each one shows its cover until you open it, so searching stays quiet. Starts off. Turn it on in Hushfeed settings > App. |
| `Stop video looping` | Stops a video at its end instead of playing it again. Starts off. Turn it on in Hushfeed settings > Playback. |
| `Story controls` | Lets a story replay when it ends instead of moving on, and keeps a photo story on screen until you tap or swipe. Starts off. Turn it on in Hushfeed settings > Playback. |
| `Subtitle tools` | Lets you make captions bigger, change their background and keep them in clear display, bring back TikTok's captions switch where it's missing, and save subtitle files with downloaded videos. Starts off. Turn it on in Hushfeed settings > Feed screen, and Downloads for subtitle files. |
| `Swipe-left controls` | Lets a left swipe on a video open its comments or do nothing, instead of opening the creator's profile. Starts off. Pick an action in Hushfeed settings > Feed screen. |
| `Translate comments` | Translates comments as they load, using TikTok's own translator, so you don't have to tap Translate on each one. Starts off. Turn it on in Hushfeed settings > Comments, where more rows list languages TikTok's automatic translation should leave alone, keep videos in the language they were made in, pick the language translations come out in, and add TikTok's translate button beside the comment count. |
| `Trust user certificates` | Lets TikTok trust security certificates you install yourself, so a tool like mitmproxy can show what the app sends. The risk: anyone who gets a certificate onto your phone can read TikTok's traffic too. Use it only on a test phone. |
| `Turn off haptics` | Stops the little vibrations TikTok makes when you tap or hold things. Your keyboard and your phone's own vibrations stay. Starts off. Turn it on in Hushfeed settings > App. |
| `Turn off screen transitions` | Opens and closes TikTok's screens without the sliding animation, so moving around feels quicker. Swipes still follow your finger. Starts off. Turn it on in Hushfeed settings > App. |
| `Use non-personalized search` | Shows search results that aren't tailored to your account. Starts off. Turn it on in Hushfeed settings > App. |
| `Use system font` | Shows TikTok's text in your phone's own font or in a .ttf or .otf font file you import, instead of TikTok's, and can use your phone's emoji too. Starts off. Turn it on in Hushfeed settings > App, then restart TikTok. |

## Help

### Logging in fails

- Turn off Private DNS, AdGuard or any other ad blocker while you log in. They can block the addresses TikTok checks a login against.
- After several failed tries TikTok stops taking new ones for a while. Wait an hour, then try again.
- Facebook login can't work on a patched build, because Facebook checks the app's signing key and yours isn't TikTok's. Log in with your email or phone number and a code, or with Google.
- If a login stalls with Hide CAPTCHA popups on, turn it off and restart TikTok. None of the Feature Gate Lab presets touch a CAPTCHA.

### Patching stalls partway

The manager has probably run short of memory. If it says the patcher ran out of memory with a 1024 MB heap, patch the bundle (`.apkm`) from [step 1](#install) instead of the APK. It needs less memory, and on a 3 GB device it finished where the APK failed. In Manager 2.0, also check that **Use embedded VM** is on under **Settings > Patcher**, close other apps and start again. If it stops while Manager is in the background, allow it under **Battery optimization** on that same page. On an older Manager, set **Settings > Process runtime** to 1,024 MB instead, or 512 MB if it still stalls. Change app name, Custom launcher icon and Hide Play Store update offer decode resources too. You can leave them out if memory stays tight, except for Hide Play Store update offer when the installed copy already used it.

### Choosing patches

Start with the recommended patches in Simple mode. Optional removal patches come with the feature losses listed in their descriptions. The release checks apply all compatible patches together. They don't cover every sign-in method or runtime combination.

Pause can't put removed files back. To restore them, patch the original file from [step 1](#install) with those removal patches left out. Use the same signing key and the same package name as the installed copy, including the Clone app package name if you used it. Install over your existing copy without uninstalling it. If that copy used Hide Play Store update offer, keep it selected so the version code doesn't drop.

### TikTok stays in English after removing language packs

Remove unused language packs keeps every TikTok language until you list some in its **Languages to keep** option. `all` or a blank option keeps them all, and a list such as `en,tr` keeps just those. English always stays as the fallback. If you first picked this patch with a bundle older than v0.67.0, the option may still say `en`, so check it. Hushfeed's own settings translations are separate from TikTok's language packs.

Pause and a settings import can't bring back a language removed at patch time. Repatch a clean official APK with that language kept, using the same package name (Clone app included) and the same signing key, and install it over the existing app so your account stays. If you used Hide Play Store update offer, keep it selected so the version code doesn't drop.

### A second TikTok beside the store app

Select Morphe's Clone app patch, with its Update permissions and Update providers options left on, and Hushfeed's Run beside the store app with it. Clone app alone gives the copy a new package name that TikTok's servers won't issue a device ID to, so logging in fails. Change app name helps tell the two icons apart. Google and Facebook sign-in check the package name and signing key, so log in to the copy with your email or phone number and a code.

### Is it Hushfeed? Pause it and see

**Hushfeed settings > Pause Hushfeed** (under the Hushfeed heading) turns off the runtime features from the next start. Your settings stay as they were, and turning Pause off brings them back after a restart. A diagnostic export made while paused says so at the top. If a problem stays while paused, compare with an official TikTok build before you blame any patch.

Pausing turns off the screen-time budget as well, so with Wait a day to loosen the budget on, Pause is refused until the day starts over. App lock stays on, so pausing can't be used to get past it.

A few patches change TikTok with no switch in front of them, and Pause can't reach those:

- Settings itself, which is how you get back to the switch.
- Accent color, AMOLED dark theme, Hide Play Store update offer, Change app name and Custom launcher icon.
- Disable screen capture detection, Disable login requirement and Fix Google login.
- Block P2P video relay and the five patches that take files out of the app: Remove content credential and card scanner assets, Remove unused language packs, Remove creation tools, Remove LIVE extras and Remove voice tools.
- In Downloads, the folder and file name you picked still apply, as do the fallback to a clean video address and the save button on stickers.

### TikTok closes right after it opens

If TikTok crashes within a minute of starting three times in a row, Hushfeed pauses itself on the next start, and the top of Hushfeed settings offers **Turn Hushfeed back on**. TikTok may clear most of what it has saved after those crashes, so you might see its interest picker again, but your Hushfeed settings survive.

If you can't reach the settings at all, create an empty file named `hushfeed-safe-mode` in `Android/data/com.zhiliaoapp.musically/files/`. From a computer with adb that's `adb shell touch /sdcard/Android/data/com.zhiliaoapp.musically/files/hushfeed-safe-mode`. Hushfeed stays paused while the file is there, and Turn Hushfeed back on deletes it.

### A save didn't finish

If TikTok closes while saves are still going, the next time you open it the download recovery banner names the ones that didn't finish, or that Hushfeed can't confirm, and how many of their files are missing. Files that already reached your gallery count as saved and are left alone. Nothing picks a save up again by itself, so save it again if you still want it. The note Hushfeed keeps for this stays inside TikTok's own storage and holds no links or tokens. On Android 9 and older, a file that's still being written is noted by its own path, which is the name it's saved under and can include the creator and the video ID, until it's confirmed. Each save's entry goes when the save ends or once the banner has named it.

In unreleased source builds, LIVE recordings keep recovery files inside the app until Hushfeed verifies the saved copy. After an interruption, Hushfeed tries to save the complete part. If it can't, Retry opens the retained recording's actions. You can retry saving it, export its files or delete them. Older files with an unknown save history and damaged recovery records offer export or deletion. Finish that recovery before starting another recording. Clearing the app's data or uninstalling it removes these files.

### A photo post saved as a video still has TikTok's watermark

A post that's one photo with a sound has no video on TikTok's servers, so TikTok's own Download builds one on your phone and adds its logo, the creator's handle and an end card. Choose Download image for a clean copy of the photo. Or turn on **Save photo posts as a video** under **Hushfeed settings > Downloads**, and Download video makes the MP4 itself from the original photo and the post's sound, with no marks.

### A LIVE bid or a payment fails

A bid from a patched TikTok can fail with "Bidding is temporarily unavailable", and other payments may be refused the same way. TikTok's security library reads the app's signing certificate while it signs those requests, and a patched TikTok carries your manager's. Bid and buy from the official app.

### Profile and Inbox are pure black, but I didn't pick AMOLED dark theme

That's TikTok's own dark mode. It paints Profile and Inbox black and leaves the comment, share and search sheets dark gray. AMOLED dark theme is what turns those sheets black as well.

### Coming from Kveld

Hushfeed includes the eight Kveld TikTok optimizers it didn't already have, and its Feed filter and Disable telemetry cover Kveld's Feed Ad Blocker and Npth telemetry. Remove or turn off Kveld's TikTok patches after you switch. Kveld marks those eight shared names on by default, so keeping both sources selects them whether you wanted them or not.

## Going back

- **To plain TikTok.** Save your settings first if you might come back (**Backup and restore > Back up settings**). Then uninstall the patched TikTok and install TikTok from Google Play. Android won't put one over the other because they're signed with different keys, and uninstalling clears the app's data, so you'll log in again.
- **To an older Hushfeed.** Download that release's `.mpp` from the [releases page](https://github.com/SysAdminDoc/hushfeed/releases), load it in Morphe Manager as a local bundle, then patch and install as usual. With the same signing key it installs over the current build and keeps your login and settings.
- **Keep the signing key.** Android only installs an update over the old app when both carry the same key. Don't reset the manager's key, and take it with you when you move to a new phone or reinstall the manager. A build signed with a new key means uninstalling first.

## Supported target

- App: TikTok, the global package `com.zhiliaoapp.musically`
- Version: [47.5.3](https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/tiktok-47-5-3-release/tiktok-47-5-3-2-android-apk-download/), on APKMirror since 2026-10-22.
- Build: version code 2024705030, arm64-v8a and armeabi-v7a, nodpi, minSdk 23
- SHA-256 of the APK every patch was verified against: `6670ca3f93bfea379658cad3c27f05d3a380b23a0d82569cce684b7453e8ff3e` (47.5.3)
- Also [PineDrama 47.5.3](https://www.apkmirror.com/apk/tiktok-pte-ltd/pinedrama-short-dramas/pinedrama-short-dramas-47-5-3-release/), TikTok's drama app (`com.ss.android.ttmd.video`, version code 2024705030). It's built from TikTok's code, and 95 of the patches apply to it. Morphe Manager lists only those. APKMirror has it as an `.apkm` bundle, which Manager merges for you.

On Windows, check your download with `Get-FileHash -Algorithm SHA256 .\tiktok.apk`. On Linux or macOS it's `sha256sum tiktok.apk`.

Hushfeed declares one TikTok build at a time, the newest stable one it's been checked against. When a newer build is supported, the old one is dropped in the same release. On any other build a patch can fail loudly when its anchor moves, or worse, land in the wrong place.

### Why you have to fetch that APK yourself

Google Play only ever serves the newest build it thinks your device can run, so the copy on your phone may not be 47.5.3, and Play can't be asked for an older one. Take the file from APKMirror (step 1 of [Install](#install) says which of its two) and patch that, not the installed app.

APKMirror also offers some TikTok releases as bundles, using an `.apkm` file, and 47.5.3 has one beside the APK. Morphe Manager merges a bundle into a single APK and keeps TikTok's file paths, so everything works on it, and it needs less memory for the bundle than for the APK. The desktop CLI's merge moves the resource files around, and Accent color, AMOLED dark theme and Custom launcher icon refuse that result, so on a computer patch the plain 47.5.3 APK instead.

## Verify a download

The [latest release](https://github.com/SysAdminDoc/hushfeed/releases/latest) ships `SHA256SUMS.txt` beside the `.mpp`. Hash your download with `Get-FileHash -Algorithm SHA256 .\patches-<version>.mpp` (or `sha256sum` elsewhere) and compare it with the line for that file.

The release also carries `release-receipt-<version>.json`. It records the tag and full commit that built the bundle, the bundle's size and hash, and the TikTok APK every patch was applied to, with a verdict for each patch. The bundle is byte reproducible, so you can also build that commit yourself and compare the checksum.

## Building from source

You need JDK 21 or newer, an Android SDK set in `local.properties`, and `GITHUB_ACTOR` plus a `GITHUB_TOKEN` with `read:packages` for the Morphe dependencies on GitHub Packages. On Windows, run the runtime tests on JDK 25, since JDK 21 there couldn't replace a file through `File.renameTo`.

```bash
./gradlew :extensions:tiktok:test
./gradlew :patches:test
./gradlew :patches:generatePatchesList
pwsh -File scripts/validate-release-facts.ps1
./gradlew :patches:buildAndroid
```

The patch tests read the stock TikTok APKs from the folder `HUSHFEED_FIXTURE_DIR` names, which [CONTRIBUTING.md](CONTRIBUTING.md) covers along with the release steps. The finished bundle is `patches/build/release/patches-<version>.mpp`, with `bundle.sha256` beside it. Publish that one. The copy in `patches/build/libs` gets turned back into a plain jar by later Gradle tasks, and Morphe Manager then shows zero patches. Two clean builds of the same commit give the same file and checksum.

For Android 17 emulator checks, use Android SDK Command-line Tools 23 and inspect the new AVD's `.ini` target before launching it. Tools 12 can misread `android-37.1` as `android-0` even when creation succeeds. Run `python scripts/verify-native-alignment.py <apk>` to check native LOAD alignment, stored ZIP offsets and RELRO ranges that overlap writable data on 16 KB pages. Safely isolated RELRO LOAD segments are accepted. A 16 KB image still needs actual TikTok launch and playback checks. A static pass doesn't prove it runs.

To add a language to the settings screen, copy one of the tables under `extensions/tiktok/src/main/l10n/` (the tab separated `de.tsv` or the Weblate style `in.csv`) to your language code, translate the right column, then run `python scripts/gen-l10n.py` and `./gradlew :extensions:tiktok:test`. Use the code Android reports, so `in` rather than `id`. The tests fail on any settings text that's missing from a table.

The patch audit's catalog check is `pwsh -File audit/run-audit.ps1 -Validate`. To check a saved report without changing it, use `-ValidateResults -Results <report> -OutDir <evidence folder>`. A current result needs its exact definition, build receipt and APK identity, unchanged evidence files, and the same executable inputs as the build being qualified. Each check keeps its own input digest, so a partial rerun cannot qualify older checks. Documentation changes alone leave the digest unchanged. Old or mixed-build records stay historical. The [audit routine](concepts/patch-development.md#patch-audit) covers the device builder's `.audit-build.json`, shared device leases and receipt-bound screenshot verdicts.

Unreleased release checks require a separate fixture and native audit for every package and version in the catalog. Each record includes the source APK's manifest and hash, the selected patches and options, and the exact bundle and source commit. A whole-app walk also verifies the installed APK before and after navigation. Older published receipts remain readable as historical records. PineDrama still needs its own native audit and walk profiles before these checks can permit another publication.

On Windows, device builds, probe installation, the probe tools, the injected-register check and audit runs need `-LeaseDir <shared lease folder>` and `-ChatIdentity <task identifier>`, or `HUSHFEED_DEVICE_LEASE_DIR` and `HUSHFEED_CHAT_IDENTITY`. Every device command checks the exact serial and current ownership. Existing leases are never reclaimed automatically, including expired ones. Local builds and offline audit checks don't acquire a device lease.

Where things live:

- `patches/`: the Kotlin patches and their fingerprints.
- `extensions/tiktok/`: the Java code that runs inside TikTok, with its Robolectric tests. The settings translations are in `src/main/l10n/`.
- `scripts/`: patching and checking scripts. `release/release.ps1` runs a release in five stages (`prepare`, `preflight`, `build`, `publish` and `index`), and `validate-release-facts.ps1` holds this README and the index to the catalog.
- `tools/`: release text and What's new generation, plus the test-only verification probe a device check drives.
- `audit/`: the patch audit. `catalog.json` says how to prove each patch works on a phone, `run-audit.ps1` runs those checks, and `results/` keeps what each TikTok version showed. The steps for a new version are in the [development map](concepts/patch-development.md#patch-audit).
- `patches-list.json` is the generated patch catalog. `patches-bundle.json` is the Morphe source index for the published bundle and only changes at release time. Starting with the next release, its description includes that release's grouped change list so Morphe Manager can show it right away.
- [App and patch development map](concepts/patch-development.md): the target boundary, the source modules and how to add a patch. The research lives beside it in the [TikTok app audit](concepts/tiktok-app-audit.md), the [network and power measurements](concepts/runtime-observation.md) and the [measured TikTok runtime](concepts/runtime-results-2026-10-09.md).

### Add-on bundles

A bundle patched in beside Hushfeed can hang its own settings off Hushfeed's. These stay put, and `AddonContractTest` fails if one changes: `TikTokPreferenceFragment`'s `createMasterMenu(Context, PreferenceScreen)` and `onResume()`, the home page rows keyed `section_interface`, `section_feed_filter`, `section_feed_navigation`, `section_playback` and `section_screen_time` above the Hushfeed heading, and the public `SettingsMenuPreference`, `SectionHeadingPreference`, `SettingsHeaderPreference`, `SettingsUi`, `L10n` and `HushfeedPause` members that test lists. Anything else in the settings code can move between releases.

## Privacy

Hushfeed has no server of its own and collects nothing. Everything it adds runs inside TikTok on your phone.

It goes online by itself for one job. When you save a video, a photo, a sound, subtitles, a sticker or a profile picture, it downloads that file over HTTPS from the address TikTok's own data holds for it. It won't fetch from an address on your own network, and those downloads connect directly even when Network proxy is on. Copy the full link for short links, which is off unless you turn it on, uses the same path to see where a vt.tiktok.com, vm.tiktok.com or tiktok.com/t/ link leads. Network proxy, once you turn it on, also checks that your proxy answers when TikTok starts and when it comes back to the screen. Anything else, like translating a comment or blocking a creator, goes through TikTok's own code to TikTok's servers.

The only web addresses written into Hushfeed's code are github.com for this project, ko-fi.com for the Support Hushfeed row, which opens in your browser only when you tap it, gitlab.com and gnu.org for licence texts, tiktok.com for the share links TikTok itself uses, and music.youtube.com for the Find the sound on YouTube Music gesture. A test fails the build if another one turns up.

The diagnostic report and a feed capture stay on your phone until you copy or save them. They leave out web addresses, login tokens and cookies, device ids, the ids of videos, comments and messages, and creator names and handles. The report does name the Hushfeed settings you've changed, so a bug report shows which switches are on, but never the words, names or addresses you typed into one. Read one through before you share it anyway.

## Credits

Hushfeed stands on a lot of other people's work, and the licence asks that this stays visible.

- [icysymmetra/tiktok-patches-for-morphe](https://github.com/icysymmetra/tiktok-patches-for-morphe), the Metra patches this project was forked from. Most of the original patch set, the settings framework and the Feature Gate Lab come from there, as does the release history below 0.8.0 in the changelog.
- [kveld9/kveld-morphe-patches](https://github.com/kveld9/kveld-morphe-patches/tree/fcb1768620b8f98a6dd31e801074589ce9a63356) for the eight optional TikTok optimizer patches and the extra Npth telemetry coverage, adapted from v1.23.1 at commit `fcb1768620b8f98a6dd31e801074589ce9a63356`.
- [ReVanced](https://gitlab.com/revanced/revanced-patches), whose TikTok patches the whole lineage continues, and [RookieEnough/De-Vanced](https://github.com/RookieEnough/De-Vanced), which upstream was built from.
- [hxreborn/hxreborn-tiktok-patches](https://github.com/hxreborn/hxreborn-tiktok-patches) for the inbox injectors, the telemetry patch, the risk control CAPTCHA hook and several feed card filters.
- [BlueDragon4251/tiktok-patches-for-morphe](https://github.com/BlueDragon4251/tiktok-patches-for-morphe) for the seen video filter, the gate recorder and the download quality ideas.
- [eduardo3677-ai/tiktok-patches-for-morphe](https://github.com/eduardo3677-ai/tiktok-patches-for-morphe) for Ghost mode.
- [na-x-ril/onlynazril-patches](https://github.com/na-x-ril/onlynazril-patches) for the rule Hide CAPTCHA popups uses to tell a browsing puzzle from a sign-in one, Show commenter handle and region, the imported font in Use system font, and Profile background.
- [@lyyako](https://github.com/lyyako) for the sanitize sharing links hook, the seekbar patch, the anti-recording patch, `Open external links directly` and `Always show publish date`.
- [@oscski](https://github.com/oscski) for `Disable the long press repost`.
- The [Morphe](https://github.com/MorpheApp) team for the patcher, the manager and the patches template.

Files that came from another project keep their original notices, and files written here say so in their header. A test holds every source file the bundle ships to having one, so a file cannot arrive without saying where it came from.

The notices are also in the app, under Hushfeed settings > About Hushfeed > Licenses, because Morphe asks that they reach the person using the software and not just the person reading the source.

## Notes

- Hushfeed is not affiliated with TikTok, ByteDance or Morphe. "For Morphe" describes compatibility, nothing more.
- Patching a client TikTok didn't ship is your call. Some accounts see CAPTCHA checks or find that follows don't land on patched builds. Follow diagnostics says so when it happens.
- Everything Hushfeed adds runs inside TikTok, as TikTok, with TikTok's permissions. Installing a patched build is the same trust decision as installing any app, and the source is here so you can read it first.
- This repository and its [GitHub releases](https://github.com/SysAdminDoc/hushfeed/releases) are the only official source. Anything else offering a Hushfeed build wasn't made here.
- Bugs and concrete feature requests go in the [issue tracker](https://github.com/SysAdminDoc/hushfeed/issues), with the TikTok version and the patch involved. Questions and general chat go in [Discussions](https://github.com/SysAdminDoc/hushfeed/discussions), where release news is posted too.

## License

GPLv3, inherited from the projects Hushfeed was built on. See [LICENSE](LICENSE) and [NOTICE](NOTICE). The same notices are reachable on a patched phone under Hushfeed settings > About Hushfeed > Licenses.
