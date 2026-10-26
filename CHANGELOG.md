# Changelog

Every Hushfeed release, newest first.

## Unreleased

### Fixes

* **Diagnostics:** First-frame callback records keep source ownership and monotonic timestamps for startup checks (#150).
* **Build checks:** Release checks require matching APK and native evidence for every advertised package. Walks verify the installed build and patch options.
* **TikTok:** Recording recovery exports use bulk writes to avoid long waits when saving large files.
* **TikTok:** Recovery Retry keeps the recording's original stop reason instead of saying TikTok closed. Recovery dialogs follow Hushfeed's theme.
* **TikTok:** Screen recordings check storage as they run and keep recovery files until the saved copy is verified. Interrupted recordings can be retried, exported or deleted.
* **Build checks:** Patch audits now check the installed APK and selected patches. Results from a different package or an excluded patch stay historical.
* **Build checks:** Patch audits check complete diagnostics before reporting missing switches. Missing videos leave dependent checks inconclusive, and the commenter-region check uses the current cache trace.
* **TikTok:** Screen recording keeps distinct timestamps for its earliest frames while sound starts.
* **TikTok:** Screen recordings keep their first video frames while the audio encoder starts.
* **Build checks:** A patch audit now stops qualifying a build when its executable inputs change. Partial reruns leave older checks historical.
* **TikTok:** Screen recording releases audio resources when sound can't start. Its save notice now says when the recording has no sound.
* **TikTok:** Screen recording now asks Android to capture only the patched app's sound.
* **TikTok:** Service+ cleanup now checks the first entry in a LIVE preview, matching TikTok's own display logic (#94).
* **TikTok:** The comment pause can identify a playing first frame before the first progress report arrives (#151).

## 0.79.0 (2026-10-24)

### Features and fixes

* **TikTok:** On PineDrama, Hushfeed's settings now tell you to restart PineDrama instead of TikTok. That covers the note under each switch, the restart button that shows up once you flip one, the saved message and the Feature Gate Lab, in English and all nine translations. Other mentions of TikTok, like its servers or the TikTok Sans font, stay as they are (#156).
* **TikTok:** New `Hide spam comments` switch in Hushfeed settings > Comments, off by default (#187). It hides bot comments that send you to their profile or a "link in bio", spell out a website, hand out a Telegram or WhatsApp contact, or offer free followers. It knows the common phrasings in English and the languages Hushfeed's settings come in, plus French. Your own comments stay, and so do the creator's and any they pinned or liked, so a "part 2 in my bio" from the creator still shows.
* **TikTok:** `Hide videos you have already seen` takes watched videos out of For You again (#172). TikTok reads each new page once before handing it to the feed. The switch sits that first read out, as it does on lists you open yourself, but everything the read kept was then remembered as allowed and never checked again. Now the page gets checked against what you've watched once TikTok hands it over, and your watch record starts loading during that first read so it's ready in time.
* **TikTok:** With AMOLED dark theme, the `Add comment...` bar under a photo post you open from search, a profile or a link is black now (#171). TikTok leaves that bar see-through, and a photo runs on underneath it, so the photo showed through where a video post shows black.
* **TikTok:** `Use an imported font` now works on PineDrama (#243). PineDrama doesn't carry TikTok's own fonts, so its text fell back to your phone's font and the imported one never got used. It now draws in your font at the weight the app asked for, so bold text stays bold.
* **TikTok:** Feature Gate Lab's `Scrolling comments on videos` preset tells you before you apply it that comments only scroll on videos TikTok has turned them on for. Each video carries its own flag from TikTok's server, so most videos look the same with the preset on (#98).
* **TikTok:** New `Allow photo comments` patch for PineDrama, with its switch off by default (#156). PineDrama hides the gallery tab in the comment box for everyone, even though TikTok's server allows photo comments. Turn the switch on in Hushfeed settings > Comments and restart, and the comment box asks the server like TikTok does.
* **TikTok:** PineDrama moves to 47.5.3 and 47.4.3 is dropped, so grab the new PineDrama from APKMirror before you patch it again. Six more patches run on it now: `Advanced downloads`, `Automatic video advance`, `Hide inbox items`, `Hide profile shortcuts`, `Open the comments at full height` and `Show the progress bar`. PineDrama 47.5.3 has no stories, HD upload choice or foldable comment panel, so `Story controls`, `Always upload in HD` and `Foldable split comment view` are TikTok only now. That makes 95 patches Morphe Manager offers for PineDrama. The rest lean on TikTok's LIVE rooms, feed buttons or resources that PineDrama doesn't have (#156).
* **TikTok:** New `Report a newer phone to effects` switch, off by default (#176). Effects ask TikTok's server whether your phone can run them, and on some older phones the answer is "This effect doesn't work on this device". With the switch on, the effects server is told you have a Galaxy S24 Ultra. Sign-in and everything else still see your real phone. An effect your phone really can't run may still fail or look wrong.
* **TikTok:** New `Load all saved stickers` patch, with no switch. PineDrama's sticker panel loaded only the first 100 stickers you saved and never asked for more, so the rest were missing from Saved. It now asks for all of them in one go. TikTok gets the same first request, and it still loads more by itself past that (#214).
* **TikTok:** New `LIVE recording source` choice under `Record LIVEs you watch`. `Stream only` saves the room's own video like before. `Screen` records what your phone shows while you watch. Android asks to allow screen recording each time, and Stop saves it. The app's sound comes along when TikTok has the microphone permission, otherwise the recording is video only (#37).
* **TikTok:** New `Blur the video behind the bar` switch under `Liquid glass look`. It frosts the glass pill with a soft blur of the video behind it, off by default, on Android 12 and up (#231).
* **TikTok:** With `Dark LIVE panels in dark mode` on, the light pieces left in a LIVE room go dark too. The navigation bar under the menu a long press in a room opens stays dark now, even when TikTok turns it light again. The tiles in the host's profile card and the fan club's tab strip and card turn dark, and the text and icons on them turn light so they stay readable (#159).
* **TikTok:** `Tag saved videos with their details` now tags the videos TikTok actually sends. Their files carry a short list of TikTok's own fields where the tags go, and the switch left those files alone, so every save came out with no title, creator, date or link. The tags go in now, and TikTok's fields stay in the file beside them.
* **TikTok:** `Hide TikTok's Auto scroll button` also hides the Auto scroll switch that tablets and unfolded foldables show beside the avatar on each video, and it works with `Auto-advance videos` off now. Before, that switch stayed, and the hide only worked while Auto-advance was on (#234).
* **TikTok:** The README's install steps now say which 47.5.3 file to get. Morphe Manager on a phone should patch the bundle (`.apkm`), because on a device with 3 GB Manager ran out of memory on the APK and patched the bundle in about a minute. A computer should patch the APK. The out of memory tip under Patching stalls partway points to the bundle too, and step 6 says to uninstall the Google Play TikTok first, which logs you out.
* **TikTok:** With `Keep Clear display across videos` off, the tabs, the caption and the buttons now come back as your swipe lets go, so the next video arrives with them. Before, it settled full screen first and then jumped when they showed up again. A Clear display you turn on from TikTok's long-press menu also stays on now instead of switching itself off as the menu closes (#232).
* **TikTok:** With `See-through bottom bar` on, the caption, the buttons and the search bar don't drop down over the tabs for a moment after each swipe anymore (#232).
* **TikTok:** Long press set to `Save the frame on screen as a photo` works again on 47.5.3. TikTok's player now draws each video into a layer of its own under the feed, so the save only found an empty view and said the frame couldn't be read. It reads the player's layer now, and the photo comes out at the video's own size with no caption or buttons, on the feed and on videos opened from a profile.
* **TikTok:** `Remove feed ads` also takes out the smaller Lemon8 card in For You, the one titled "Your daily dose of inspiration" with a Lemon8 post and Open Lemon8 under it. It's built differently from the full-screen Lemon8 card the switch already removed, so it slipped through on 47.5.3.
* **TikTok:** The `Show how many were filtered` label and the `Show what is left of the budget` label stay off the Shop tab and the Nearby tab now. Both showed there because the Home tab stays selected on those tabs, and the filtered count sat right over their search boxes. They still show on For You and Following.
* **TikTok:** With `Dark LIVE panels in dark mode` on, a room's viewer ranking opens dark again. A shop room's Top customers ranking still keeps TikTok's light look, because its tab titles came out dark on dark (#159).
* **TikTok:** The README says how much memory morphe-desktop needs on a computer. With every patch picked on 47.5.3 it runs out at 1 GB, Java's default on a 4 GB machine, and finishes at 1.5 GB, so start it with `java -Xmx2g` (#220).
* **TikTok:** With `Double tap` set to `Do nothing`, or every corner of `Double tap by corner` set to it, a tap on a video pauses or plays the moment your finger lifts. TikTok used to hold each tap for a beat in case a second one followed, and two quick taps counted as a double tap that did nothing. Now each one counts (#239).
* **TikTok:** The build uses Gradle 9.8.2, up from 9.7.1. The build files now name the Guava advisory by its CVE number, CVE-2026-102554, because GitHub's global advisory database doesn't list its GHSA id.
* **TikTok:** The README now lists the parts of Hushfeed's settings that an add-on bundle patched in beside it can count on, and a test fails if one of them changes. Anything else in the settings code can still move between releases.
* **TikTok:** What's new now carries only the newest releases' notes, and its Full changelog button has the rest. Every release since 0.60.0 used to be built in, in all nine languages, and those notes alone were about 14% of the extension's size.
* **TikTok:** `Glass lens on the current tab` slides to the next tab even after you've sat on one tab for a while, or when TikTok takes a moment to open a tab for the first time. Before, it only slid when you tapped quickly, and in both of those cases it jumped instead (#231).
* **TikTok:** With `Automatic Clear display` on, a search result you open again from TikTok's own search clears again, even when you brought its buttons back by hand the last time you watched it. Before, it remembered that and stayed uncleared (#84).
* **TikTok:** `LIVE recording quality` also offers the presets from a LIVE room's own quality menu: Original, 1080p, 720p, 540p and 360p. If the room doesn't have the one you picked, the recording takes the closest one below it, or the closest above when nothing lower is there. A room's 60 fps streams count as the quality they're in, so 1080p in a 1080p60 room records the 60 fps stream instead of falling back to 720p (#203).
* **TikTok:** When the Download quality list stops below 720p, it now also offers the video's own H.264 or HEVC version if that one is taller. The diagnostic report has a new section for the last video you opened the list on, with each quality it offered and why any others were left out (#240).
* **TikTok:** `Show where commenters are from` finds a country more often. It remembers someone's country from their earlier comments and from videos of theirs you scrolled past, and it reads one more of the country fields TikTok sends with an account (#201).
* **TikTok:** `Latest first` on the Following tab now keeps everything below the video you're watching in date order as more posts load. A newer post that shows up late moves up right after your video, and nothing you've already scrolled past moves. It also asks TikTok for twice as many posts on the first load, so a newer post is less likely to turn up after the first video is already playing. When TikTok opens fresh, it no longer starts the Following tab on the post it saved from last time, which was usually older than everything loading under it. The first video now comes from the new, sorted list (#226).
* **TikTok:** Sorting a profile or Liked grid now has Least viewed first and Least liked first. While an order is on, the grid keeps loading by itself (up to 300 videos) so the order covers more than the first screen, and a button in the grid's corner changes the order right there. When you open a video from a sorted grid, swiping up goes on in the grid's order too, where it used to follow TikTok's. On the Liked tab the button sits under TikTok's note about who can see your liked videos instead of covering it (#180).
* **TikTok:** New switch under `Remove avatar rings`: `Open the Story when a creator is LIVE`. When someone who's LIVE also has a Story, tapping their picture on their profile or on your chat with them in the Inbox opens the Story, and tapping it again joins the LIVE. It starts off (#233).
* **TikTok:** With `Record LIVEs you watch` on, a LIVE room's Share sheet has a `LIVE recording quality` row. Tap it to pick the quality the next recording uses without going to Hushfeed settings (#203).
* **TikTok:** With `Offer LIVE quality in more rooms` on, Share > Video quality also shows in gaming and football LIVE rooms. TikTok left it out of LIVE Studio rooms and rooms that support low latency (#37).
* **TikTok:** In dark mode, the newer Video quality picker in a LIVE room's menu is readable again. Its rows sit on a white card, and Original, 1080p and the rest came out light on it, so they were hard to make out. They turn dark on the card now (#57).
* **TikTok:** With `Dark LIVE panels in dark mode` on, a LIVE room stays dark when your phone switches between light and dark while the room is open. Before, its Share sheet opened white until you left and came back (#159).
* **TikTok:** New switch under Share sheet: `Allow Add to Story anyway`. When a creator turned off content reuse, it brings back Add to Story in the share sheet (the button in the row of actions and your story's bubble at the start of Send to) and on their stories, like `Allow Duet and Stitch` does for those two. TikTok may still refuse the post (#88).
* **TikTok:** With Region spoof's `Match locale and timezone` on, chat date headers follow the preset's time zone now instead of the phone's clock (#184, #198).
* **TikTok:** New `Turn on Thoughts` switch in Hushfeed settings > App. It shows Thoughts on profiles and in your inbox, plus the prompt to share one, for accounts TikTok left out. It starts off, and TikTok's servers can still refuse a post (#186).
* **TikTok:** `Keep photos as TikTok sent them` now also covers stickers that don't move, and `Download original photos` keeps TikTok's own file when it's on (#229).
* **TikTok:** `Keep the screen's brightness in the camera` now also covers the front flash and the fill light, which used to push the screen to full brightness on their own. The flash still turns the screen white, just at your own brightness (#200).

## 0.78.0 (2026-10-22)

### Features and fixes

* **TikTok:** Hushfeed now supports TikTok 47.5.3 and drops 47.4.3. Grab the 47.5.3 APK from APKMirror and patch it with the same signing key, and your login and settings carry over. PineDrama stays on 47.4.3.
* **TikTok:** `Remove unused language packs` now patches PineDrama 47.4.3 too. Its language files list the same 25 languages as TikTok's but word the list differently, and the patch stopped at that instead of trimming them.
* **TikTok:** `Search loaded activity` puts its search bar back on the Inbox's Activity page. Since 0.77.0 the bar never showed up, because Hushfeed was still looking for TikTok's list under the names an older TikTok gave it.
* **TikTok:** New `Double tap by corner` switch under `Double tap` in Hushfeed settings > Feed screen. Turn it on and each quarter of the video gets its own double tap choice, so the top left can open the comments while the bottom right keeps TikTok's like. Off, `Double tap` works like before. The idea and first version came from @OmerKurdi79 (#232).
* **TikTok:** Settings search remembers what you looked for. With the box empty it lists your last ten searches, newest first. Tap one to run it again, tap the bin to forget it, or clear the list at the bottom. A search is kept once you press search on the keyboard, open one of its results or flip a switch it found, and the list stays out of backups (#232).
* **TikTok:** New `Keep Clear display across videos` switch in the Clear display part of Hushfeed settings > Feed screen. It's on by default, which is how Clear display has carried over until now. Turn it off and a Clear display you turn on by hand lasts for that video only, so the next one starts with its buttons showing (#232).
* **TikTok:** Patching is faster. Many patches used to search every class in TikTok for the code they change, and now they look it up by a string, a number or a name the patcher already keeps an index of. With every patch on 47.4.3, a desktop run went from 89 to 60 seconds and the patching step itself from 54 to 26. The APK that comes out is the same.
* **TikTok:** `Sort loaded videos on profile grids` sorts the videos a profile or Liked grid has loaded. You can put the newest or oldest first, the longest or shortest, or the most viewed or most liked. Pinned videos stay first, videos with no value for that order go last, and new pages are sorted in as you scroll. `Original order` is TikTok's own. It only changes videos the grid has already loaded, not the ones it hasn't fetched yet (#180).
* **TikTok:** `Fit the video to the screen` now shows photo slideshows whole on a squarer screen too. Each slide sits centered with black above and below instead of losing the top and bottom, and swiping, the dots, the caption and the music line work as before. On a normal tall phone nothing changes (#188).
* **TikTok:** New `Keep captions available` switch under Hushfeed settings > Feed screen > Captions. TikTok hides the Captions switch in its accessibility settings, and the caption controls that go with it, for some accounts and regions. Turn this on and they come back. Captions still only show on videos that carry them. It's off by default and takes a TikTok restart to apply (#217).
* **TikTok:** `Match locale and timezone to country` now makes that country's time zone TikTok's own from the moment it starts, daylight saving included. Before, only the zone TikTok reports changed. Hushfeed's own clocks (the daily limit's reset, the sleep timer and Auto streak's send time) stay on your phone's time (#184, #198).
* **TikTok:** New `Seek bar on sound previews` switch under Hushfeed settings > Playback, part of `Show the progress bar`. When you play a sound's preview on its page, a line along the bottom of the cover shows how far in you are and how long the preview runs. Tap or drag it to jump around in the preview. The line goes away when the preview stops, and a sound that can't be skipped through keeps TikTok's own controls. It's off by default (#185).
* **TikTok:** `Keep faded controls in Clear display` keeps the like, comment, save and share buttons on screen again. Newer TikTok builds hide the whole button column at once in Clear display instead of each button, so the buttons stayed away even with the switch on (#209).
* **TikTok:** The diagnostic report no longer calls the video controls broken for accounts without the Following stories button. TikTok only shows that button to some accounts, and every Clear display without it got counted as a missing part (#209).
* **TikTok:** The diagnostic report can now say which LIVE room a comment went to. Turn on `Log diagnostics`, and each LIVE comment line carries a short code for its room and for the room you entered last, so two comments can be compared without showing any room id (#110).
* **TikTok:** Hushfeed now needs Morphe Manager 2.0.0 or newer. Update Manager before you patch. It's built on Morphe's 2.0 patcher, which only unpacks the parts of TikTok a patch asks for, so patching takes less time and memory. With every patch on, a desktop run took about half as long. From a computer, use morphe-desktop 1.19.0 or newer. Manager 2.0 no longer has the `Process runtime` memory setting, so the install steps now say to leave `Use embedded VM` on under Manager's Settings > Patcher.
* **TikTok:** New `Hide Balance in the profile menu` switch under Hushfeed settings > App. It takes Balance out of the menu your profile's top right button opens. Your coins and gifts don't change. It's off by default (#194).
* **TikTok:** New `Keep photos as TikTok sent them` switch under Hushfeed settings > Downloads > Photos and stickers. Photos, covers and story photos you save stay the HEIF or WebP file TikTok sent instead of turning into a JPEG, for editing apps that read those. Some galleries and computers can't open them, so it's off by default (#229).
* **TikTok:** New `Glass lens on the current tab` switch under `Liquid glass look`. A smaller piece of glass sits behind the tab you're on and slides over when you pick another one. With animations turned off in your phone's settings it just moves. The tabs and their tap areas don't change (#231, #124).
* **TikTok:** New `Hide the TikTok Minis icons in the profile menu` switch under Hushfeed settings > App. It keeps the TikTok Minis entry in the menu your profile's top right button opens and hides the row of app icons under it, so the menu is shorter. It's off by default (#230).
* **TikTok:** New `Turn off the swipe to the side menu` switch under Hushfeed settings > Feed screen. With it on, swiping right on For You doesn't open TikTok's side menu with Your orders and TikTok Minis, so the swipe goes to the next tab instead. It's off by default (#213).
* **TikTok:** New `Don't auto translate videos` switch under Hushfeed settings > Comments. With TikTok's Always translate on, it translates video descriptions and captions by itself in For You, Following and Search. Turn this on and videos stay in the language they were made in. Comments follow their own switch, and tapping See translation still works on any video. It's off by default and takes a TikTok restart to apply (#194).
* **TikTok:** With `Accent color` on, the unread count on the Inbox tab and the small unread dots now take your color too. The LIVE bubble over the Inbox tab already did, so the bubble and the count under it didn't match. Warnings still stay red (#192).
* **TikTok:** New `Open the comments at full height` switch under Hushfeed settings > Comments. TikTok opens the comment sheet part way up the screen, and with the switch on it opens all the way to the top. Dragging it down still closes it. It's off by default (#191).
* **TikTok:** New `Keep the screen's brightness in the camera` switch under Hushfeed settings > App > System. TikTok turns your screen brighter when you open its camera, and keeps it that way through editing and posting. With the switch on, the screen stays at the brightness you set. It's off by default (#200).
* **TikTok:** New `Long-press Profile for Watch history` switch under Feed tab navigation. Holding the Profile tab opens TikTok's Watch history right away instead of four menus deep, and a tap still opens your profile. It's off by default. On accounts where holding Profile switches accounts, the switch takes over that press while it's on (the name at the top of your profile still switches) and hands it back when you turn it off (#199).
* **TikTok:** In Simplified Chinese, "Profile" in Hushfeed's settings (the bottom tab lists, `Open TikTok on` and the Profile section heading) read "company profile". It now says 个人主页.
* **TikTok:** New `LIVE recording quality` choice under `Record LIVEs you watch`. `Same as the room` keeps recording what you're watching, like before. `Highest` records the best stream the room has even while you watch it lower, and `Lowest` keeps the file small (#203).
* **TikTok:** Turning on `See-through bottom bar` while TikTok is open now keeps the video running behind the tabs as you swipe. Before, the first swipe brought the black strip under the video back, and it stayed until TikTok was restarted. Hushfeed measured the next video while it was still sliding into place and took it for a caption pushed under the tabs (#145).
* **TikTok:** New `Hide the AI Cast button` switch under Hushfeed settings > App > Posting. It takes TikTok's AI Cast button off the camera's side toolbar, the same way TikTok already leaves it out of duets, stories and the DM camera. It starts off.
* **TikTok:** New `Latest first on Following` switch under Feed filter. Each batch of posts the Following feed loads now plays newest first. TikTok still decides what goes in each batch, so a post from the next batch can be newer than the ones before it, and ads and LIVEs stay where TikTok put them. Restart TikTok after turning it on (#226).
* **TikTok:** On a Galaxy, a font picked in Settings > Display > Font size and style now shows on Watch history, Activity center and TikTok's other web pages too while `Use system font` is on. Those pages kept Android's standard font before, even though the rest of TikTok followed your pick (#7).
* **TikTok:** With `See-through bottom bar` on by itself, a portrait video now reaches the bottom of the screen behind the tabs. TikTok kept centering the picture in the taller space, so it stopped short and the bottom of the bar sat over a black band. The picture grows just enough to cover it, the same way `See-through status bar` fills the top (#145, #218).
* **TikTok:** New `Where the time goes` choice under `Show the time beside the progress bar` in Playback. Pick `Above the bar` and, in the main feed, the caption and the buttons on the right (the music disc too) move up a little so the time fits over the right end of the bar. If something else still sits on that spot, the time stays beside the bar. Picking `Beside the bar` again or turning the time off puts everything back where TikTok had it, and so does pausing Hushfeed (#173).
* **TikTok:** With `Accent color` on, the "You may like" words on the search page and the dot before each one now take your color too, whether it's a preset or the wallpaper's. TikTok sends those words with a red of their own instead of using the app's colors, so they stayed red before (#163).
* **TikTok:** Two new rows under Hushfeed settings > Downloads. `Saves right now` lists the saves under way, oldest first, and says what each one is doing or how many saves it's waiting on. Its Cancel takes a save out of line, or stops a save of several files after the one it's on. Under them, the last five saves that finished stay listed, each with what it said when it was done (like "Saved 27 videos") or a note that it was cancelled or stopped on an error. `Saves at once` sets how many saves run together, from 1 to 3, and the rest wait their turn (#27).

## 0.77.0 (2026-10-20)

### Supported apps

* **TikTok:** PineDrama moves to 47.4.3, the build APKMirror got on 2026-10-19, and 47.1.4 is dropped. The same 90 patches apply to it (#156).

### Features and fixes

* **TikTok:** A TikTok link tapped in Telegram now opens TikTok as its own app instead of inside Telegram. The two used to share one card in Recents, so going back or swiping TikTok away closed Telegram too. Other apps that open links the same way get the same fix, and links from your browser open as they always did (#227).
* **TikTok:** `Fade the video controls` reaches the avatar, like, comment, save and share buttons on TikTok 47.4.3 again, and Clear display with a fade level keeps them in sight at that level. On 47.4.3 Hushfeed looked for that column under the name of a thin strip at the top of the video, so the buttons stayed at full strength and a faded Clear display let TikTok take them all away. `Hide the right column` and `Burn-in guard`'s dimming reach the column again too (#209).
* **TikTok:** The Inbox switches work on TikTok 47.4.3 again, and the back arrow no longer shows on top of the add people icon. Hushfeed took the Inbox header for the list on 47.4.3, so it treated the header's icons as rows and showed the arrow TikTok keeps hidden there, while `Hide archive`, `Hide conversations` and the other row switches never reached a row. Turning off `Hide the add people icon`, `Hide the Inbox search button` or `Hide activity status` now only brings back what it hid, and leaves alone a header icon TikTok hides on its own (#221).
* **TikTok:** Patching needs far less memory at `Turn off haptics`, `Allow screenshots and Circle to Search` and the privacy patches. Finding TikTok's haptic calls used to make a record for every method that touched a view, about 700 MB of allocations on 47.4.3, and patching ran out of memory there with a smaller heap. The calls are now looked up by name, which takes about 20 MB (#220).
* **TikTok:** With `Hide the comment bar on opened videos` on, a video or photo you open from a sound's page keeps its spot above TikTok's red `Use sound` bar. The post used to stretch down under that bar, so the bar covered the end of the caption, See translation, the progress bar and the time (#153).
* **TikTok:** New `Liquid glass look` switch under `Floating bottom bar` draws the floating tab pill as glass. It's lighter and more see-through than the plain dark pill, brightens toward the top, and has a bright rim along its top edge where the light catches it. The tabs stay exactly where they were (#218, #124).
* **TikTok:** New `Remove voice tools` patch, off unless you pick it, removes TikTok's speech recognition files and saves about 16 MB. With it in, voice search, naming a song by listening, talking to TikTok's AI assistant and TikTok's music check on videos you post all stop working (#213).
* **TikTok:** A new Region switch, `Keep the For You feed on your real SIM`, keeps the SIM you set in `Override SIM details` for sticker search, PineDrama's episodes and the rest of TikTok, while each For You request goes out with your phone's own SIM country, operator code and carrier. With `Match locale and timezone` on, For You also gets your phone's real locale country and time zone back. TikTok reads the SIM once as it starts and uses that one answer for its feature checks and its feed alike, so the For You request is where the two can be told apart. The row needs the Region spoof patch in your bundle next to SIM spoof (#214, #215).
* **TikTok:** `Filter the LIVE feed` leaves the LIVE row in your Inbox and the Following tab's LIVE row alone, apart from blocked creators. TikTok fills those rows through the same page handler as the LIVE feed, so the viewers, followers, gaming and category rules were thinning out the creators you follow too (#212).
* **TikTok:** A copy made with `Run beside the store app` gets its bottom navigation, share sheet and video control tweaks back. The renamed copy didn't count as TikTok, so every view name written for 47.4.3 came up missing in it (#209).
* **TikTok:** `Remove content credential and card scanner assets` no longer crashes TikTok when you tap the gallery icon while replying to a comment. The photo picker started TikTok's content credential service, which loaded the emptied library and took the app down into safe mode. The service now stays unstarted, and a photo you pick carries no content credential (#211).
* **TikTok:** `Show the progress bar` also shows the bar on videos that open behind a sensitive content warning. TikTok kept treating them as covered after you tapped through, so the bar stayed off on those videos even with the switch on (#208).
* **TikTok:** `Use system font` and `Use an imported font` now reach Activity center, Watch history, Comment history and TikTok's other web pages. Those screens are web pages inside the app, so they kept TikTok's font. Websites you open in TikTok's browser keep their own fonts. An imported font over 2 MB shows up there as your phone's font instead (#7).
* **TikTok:** Hushfeed's downloads work again behind a proxy app running in fake-IP mode, the way Clash, mihomo, sing-box and Xray often run. Those apps answer TikTok's video and photo servers with stand-in addresses from 198.18.0.0/15 or fc00::/18, and Hushfeed turned every save down as a local address, while TikTok's own save still worked (#145).
* **TikTok:** Holding a verified account's profile picture saves it again. TikTok 47.4.3 puts the blue check inside the @handle's text, so Hushfeed read the handle as somebody else's and asked you to open the profile again every time.
* **TikTok:** `Hide suggested accounts` also takes away the "Suggested accounts" title on Profile > Find people. The suggestions under it were already hidden, so the title sat over an empty list.
* **TikTok:** New `Dark LIVE panels in dark mode` switch under Hushfeed settings > Playback opens a LIVE room's panels dark while TikTok is in dark mode. TikTok kept the share menu, the fan club sheet and a host's profile card white even in a dark app, so they lit up over the stream. The daily ranking and TikTok's web sheets, like viewer rankings and a shop's Top customers, keep TikTok's own look, and light mode doesn't change (#159).
* **TikTok:** `Hide the side menu button` takes the button away on accounts where TikTok builds the top bar from its own server rules. TikTok never asked whether the button should show there, so it stayed next to LIVE with the switch on (#128).
* **TikTok:** `Hide comment box buttons` also takes the photo, @ and gift buttons out of the row over the keyboard. On 47.4.3 that row is built apart from the "Add comment..." bar, so they came back as soon as you started typing, and the photo button's empty spot stayed even after it went. The gift tile in that row's "+" panel goes too, and "+" itself goes when nothing's left in it.
* **TikTok:** Turning `Hide comment box buttons` off brings the photo and @ buttons back right away. TikTok keeps the comment box around between videos, so they stayed hidden on every video until you restarted the app.
* **TikTok:** Account facts shows an account's language again on TikTok 47.4.3. TikTok stopped sending the join date, region and language with the profile itself, so every line said Not sent. The language now comes from the account's own videos on the profile, and the join date and region do too whenever TikTok sends them there.
* **TikTok:** `Copy the full link for short links` stops waiting as soon as you share to another app. When you picked More and then used Copy in Android's own share sheet, Hushfeed read that link once TikTok was back in front and swapped it, and Android showed its "Hushfeed pasted from your clipboard" notice. TikTok's own Copy link still gets the full link.
* **TikTok:** With `Show the time zone` on, a language that has no short name for your zone writes it as "GMT-4" instead of "GMT-04:00". The longer form pushed the creator's name off the row in Italian on a narrow phone and cut the time short.
* **TikTok:** Hushfeed settings search results show a setting's new value when you change it from a result and come back. They used to keep the old one, so `Caption background` still read Dark after you'd picked TikTok default.
* **TikTok:** The Likes field in `Own profile counts` is translated now. It was the only English word in that editor on a phone set to another language.
* **TikTok:** `Undo last restore or reset` in Hushfeed settings also gives the Feature Gate Lab its own Undo back. A restore that carried Lab rules threw that away, so after undoing the restore you couldn't take back the Lab change you'd made just before it.
* **TikTok:** Tests now run a whole `Save videos without sound` save of a DASH video against a stand-in server. The saved file has to be TikTok's picture stream exactly as it arrived, with no sound track, the same rotation and length, and nothing left behind in the cache.
* **TikTok:** New `Hide subscriber-only videos` switch under Feed filter. It hides the posts only a creator's subscribers can watch, the ones with a lock on them, from the feed, search and profile grids. A creator on your exceptions list keeps theirs, and your own posts always stay (#219).
* **TikTok:** Three new `Caption height` rows under Captions lift spoken captions higher, up to 300 dp: one while the phone is upright, one while it's sideways, and one for the line `Keep captions in Clear display` leaves on screen. Each only moves its own caption, and 0 puts it back exactly where TikTok had it. `Caption boldness` replaces the `Normal-weight captions` switch with seven choices from Light to Extra bold, and reaches the kept Clear display line too. If you had the old switch on, it comes back as Normal, from a settings backup as well. Thanks to OmerKurdi79, whose branch these come from (#206).
* **TikTok:** Double tap and Long press have a new choice, `Turn Clear display on or off`. It hides the buttons over the video the way TikTok's own Clear display does, and the same gesture brings them back without a like. It needs the `Remember clear display` patch, which keeps it on as you swipe, the same as after TikTok's own button (#136).
* **TikTok:** `Hide the Tako bubble` also takes TikTok's AI answer card out of search results. That's the "TikTok Tako · AI summary from" card that answers a search typed as a question, above the videos. The rest of the results move up where it was.

## 0.76.0 (2026-10-18)

### Supported apps

* **TikTok:** Hushfeed now supports TikTok 47.4.3, and only that one. 47.1.4 isn't supported anymore, so grab the 47.4.3 APK from APKMirror and patch that. PineDrama stays on 47.1.4.

### Features and fixes

* **TikTok:** `Show only mutual friends on the Friends tab` goes by who reposted a repost again, not by whoever posted the video. 47.4.3 moved reposts into the ordinary Friends cards, so a friend's repost of a stranger's video was being hidden. The feed filters also reach the Reposts list on the Friends tab now.
* **TikTok:** New `Show video length on profile grids` switch, beside `Show dates on profile grids`. It puts each video's length under its view count, on the same line as the date when that's on too. Photo posts and videos TikTok sent no length for get nothing added (#181).
* **TikTok:** `Comments beside the video` now really opens the comments next to the video on a wide window, in PineDrama as well. TikTok's server tells phones they aren't tablets, and the feed then skipped the side panel even with the switch on, so comments still slid up over the video (#26).
* **TikTok:** With `Caption background` on Dark or Black, the strip's corners are TikTok's small rounded ones again. The last fix copied TikTok's shape, and on some phones that copy came out much rounder than TikTok's own captions (#158).
* **TikTok:** With Accent color's Follow the wallpaper option, the follow plus under a creator's avatar takes the wallpaper color too. It's a picture with the red drawn in, so it used to stay red. Now the patch splits it into a white plus and a circle the phone colors (#163).
* **TikTok:** `Hide the comment bar on opened videos` also hides the Message bar that shows up in that spot on a mutual friend's video. It used to stay behind and sit over the caption (#153).
* **TikTok:** When the music disc is the last button on the right side, it no longer sits on top of the progress bar and the time after the comment bar is hidden. The buttons now move up far enough to clear the disc too (#153).
* **TikTok:** `Hide search suggestions` also stops the trending search that rotates in the grey text inside the search box. The box keeps its plain "Search" placeholder, and tapping search on an empty box doesn't run TikTok's pick anymore. PineDrama's search box gets the same treatment.
* **TikTok:** `Hide the Tako bubble` also takes the "People also ask Tako" block out of search results. That's the list of questions with the Tako icon that sits between the videos, where each question opens Tako. The videos close up where it was.
* **TikTok:** `Remove feed ads` also hides the hotel deals block in search results, the one with a cashback banner over hotels priced by booking sites. A landmark's place card and the Must Eat lists stay, and so do videos tagged with a hotel.
* **TikTok:** With `Show the time to the second` on, a story's header shows when it was posted to the minute, like "3:04 PM", with the day in front when the story is from yesterday. Stories used to get the feed's long text with seconds and the time zone, which crowded the creator's name off the line.
* **TikTok:** `Hide CAPTCHA popups` works in the first seconds after TikTok opens. TikTok checks in with its account servers by itself at every start, and the switch took that for you changing your sign-in, so a puzzle in those first seconds always showed. Real sign-in changes still let their puzzle through. With `Log diagnostics` on, the log also names the address of the first action that let a puzzle through (#111, #87).
* **TikTok:** New `New device identity` row on the Privacy page, under `Hide root`, and PineDrama has it too. Tap it and restart, and the app wipes its data and comes back with a new Android ID and advertising ID, so TikTok's servers hand it a new device ID instead of the old one. Clearing the app's data in Android isn't enough for that, since the servers give the old device ID back when they see the same two IDs again. You'll be signed out, and your drafts and the offline videos saved inside TikTok go with the rest of its data. Hushfeed's settings, save records and seen history stay, and tapping the row again before the restart cancels it.
* **TikTok:** Stopping a backup or an export while the file app had it no longer counts as a Hushfeed error, in PineDrama either. With the error toast debug option on, a toast used to pop up over the row's own answer once the file app finally let go, and the diagnostics log filed the stop under errors.
* **TikTok:** Feature Gate Lab's Import loaded values takes a real export again. Since September it turned down every one, the Lab's own included, as "isn't a loaded-values export, or it's larger than the Lab accepts", because a full export holds about 15,000 values and the import stopped at 1,024. A file that's been unpacked from its .gz imports too. When the values that differ would take the Lab past its 1,024 overrides, it now says how many there would have been and changes nothing.
* **TikTok:** When Feature Gate Lab turns something down and says why, like a change past its 1,024 overrides or a loaded-values file from another TikTok version, that's no longer logged as a Hushfeed error. With the error toast debug option on, a toast used to cover the Lab's own answer. A file the Lab can't read at all still counts as an error.
* **TikTok:** `Remove unused language packs` no longer stops the whole patch run when its list names a language TikTok doesn't have, like `sr` (TikTok has no Serbian pack). That code is skipped and the patch log says so. A code that isn't a language at all still stops it, and the message now lists the codes this TikTok carries (#196).
* **TikTok:** `Report a problem` leaves the bug form's What went wrong box empty, with What happened and What I expected shown as a hint. When the link filled them in, the box came back untouched on every report, and two people said it wouldn't take their text (#197, #201).
* **TikTok:** Photo posts Hushfeed saves itself now count for `Check for already-saved videos` and get the ✓ from `Mark saved videos`. That covers photos saved with `Download original photos` on, posts saved with `Save photo posts as a video` on and anything picked on a profile grid. Saving one again offers Open or Save again, like a video does. Before, only videos were remembered (#197).
* **TikTok:** Photo posts picked on a profile grid are saved as one video when `Save photo posts as a video` is on, the same as Download does on a single post. The picker used to save their photos every time (#197).
* **TikTok:** Hiding Like in `Hide buttons in the right column` closes the column up like the other buttons do. Its row stayed behind as an empty space between the avatar and the comments (#190).
* **TikTok:** New `Hide reply previews` switch on the Comments page. TikTok opens one reply under some comments before you tap View replies, often the creator's. With the switch on, those comments show just View replies, and tapping it still loads every reply, that one included (#193).
* **TikTok:** `SIM country preset` now lists Serbia, Croatia, Bosnia and Herzegovina, Slovenia, Montenegro, North Macedonia, Albania and Bulgaria too. Each one fills in a real carrier from that country, and with `Match locale and timezone to country` on, TikTok gets that country's time zone (#198).

### Verification

* **TikTok:** The native alignment checker now catches RELRO ranges that would make writable data read-only on 16 KB devices. Safely isolated RELRO segments remain accepted.
* **TikTok:** The patch audit now checks saved results against their definitions, tested APK and build receipt, including the actual evidence files. Older or incomplete runs stay marked historical. Screenshot verdicts can't be applied to a different run.
* **TikTok:** Device builds and audits now require an exclusive lease before accessing a test device. An expired or lost lease stops device commands. Local builds still work without one. The probe tools (strip-hunt, record-markers and every probe action) and the injected-register check take the same lease now, and they won't start while another task holds the phone.
* **TikTok:** With `Log diagnostics` on, each card in the search results also logs its type codes (origin and token type, whether it's shopping, its popup type) and the names of the keys in its data. Of what those keys hold, only one-word type codes get logged, so the search and the card's text stay out of the log. That's what it takes to tell a hotel price card or a leaked search ad apart from a normal place card.
* **TikTok:** `scripts/phone.sh` checks the device lease itself now, before every adb call. Run on its own without the lease file and token, or with someone else's or an expired one, it refuses and sends nothing to the phone. strip-hunt and record-markers hand it theirs.
* **TikTok:** The injected-register check compares against the 47.4.3 APK. It still expected 47.1.4 and refused every current build.

## 0.75.0 (2026-10-16)

### Features and fixes

* **TikTok:** With `Hide the comment bar on opened videos` on and Like the lowest button left on the right, the like count no longer sits on the progress bar and the time beside it. The buttons already moved up to clear the bar, but they stopped at the heart and left its count under it (#145).
* **TikTok:** `Show translation options in Language settings` is now `Bring back TikTok's translation`, and it brings back See translation under video descriptions and in captions as well. TikTok takes translation away from some accounts with one setting on its side, which hid the button on videos along with the Language page rows. Turning it on asks for a restart now, since TikTok checks that setting once when it starts (#143).
* **TikTok:** With `Log diagnostics` on, an exported report now notes each LIVE room TikTok enters and whether a LIVE comment went to that same room. It's for comments that show for you but never reach the room (#110). Room ids and what you typed stay out of it.
* **TikTok:** A long video on a slow connection saves now instead of stopping with "The video couldn't be saved". Every save had two minutes, and a big file was cut off while it was still coming in. A download that keeps moving gets up to 20 minutes, and one that goes 30 seconds without any data still stops (#179).
* **TikTok:** Hide the rewards shortcut shows a restart button now when you flip it. Hushfeed hides the gift box as TikTok draws the profile, so a profile that was already open kept it until TikTok restarted.
* **TikTok:** Copy bio and IDs puts Copy username and Copy user ID on the Share profile sheet in Settings and privacy too. That sheet only has Send to and the apps row, so the buttons never showed up there. They now sit under the apps.
* **TikTok:** Turn off screen transitions works again. TikTok asks for its own slide after the screen has opened, later than Hushfeed used to ask for none, so pages kept sliding in with the switch on. Hushfeed asks again after TikTok now, and pages like Hushfeed's settings open with a plain cut.
* **TikTok:** Hide suggested accounts shows a restart button now when you flip it. The Friends tab and profiles keep the suggestion cards they've already drawn until TikTok restarts, so turning it on while TikTok was open seemed to do nothing there. The Inbox still changes right away.
* **TikTok:** Copy the full link for short links now works with Clean up shared links off. TikTok shortens a link that still has its tracking on its own servers, after Hushfeed has already seen it, so the short link reached the clipboard untouched. It also takes the tiktok.com/t/ short form now, not only vt.tiktok.com and vm.tiktok.com.
* **TikTok:** Turning Default speed off now counts from the very next video. Before, the video after it still played at the old default, since TikTok hands each video the last one's speed when you swipe and Hushfeed took that for a speed you'd picked. Speeds you pick yourself work as before.
* **TikTok:** Remove content credential and card scanner assets and Remove creation tools no longer leave parts of TikTok in English on a phone set to another language. Both emptied small code packages that TikTok loads every time it starts, and when one was empty, buttons and other text of TikTok's own came up in English. Those packages now stay. Remove content credential and card scanner assets no longer takes out TikTok's on-device AI files either (Stop on-device AI profiling still keeps that AI from starting), so it now saves about 11 MB, and Remove creation tools about 38 MB. Patch again to get your language back (#125).
* **TikTok:** The Video quality list in a LIVE room's Share menu no longer shows its qualities white on white in dark mode. Text in that list that can't be read against what's behind it now turns white on a dark row and dark on a light one. Light mode doesn't change (#57).
* **TikTok:** With Automatic Clear Display on and no delay, a video opened from search starts cleared. Before, it showed its buttons, its caption, the comment box and the "find related content" bar along the top for about a second before they went, every time a result was opened. TikTok now opens those videos inside its main screen rather than a page of their own, so the hold that keeps the controls away until the clear lands never started there (#84).
* **TikTok:** With Hide the Tako bubble on, a caption that already fits no longer shows a "more" on videos with Tako content. That "more" led into the search sheet, so with the sheet skipped it looked like a button that did nothing. These captions now behave like the ones on any other video: "more" only when the text is cut off, and a tap expands the caption in place. Turn the switch off, or pause Hushfeed, and TikTok's own captions come back (#162).
* **TikTok:** Accent color has a new patch option, Follow the wallpaper. On Android 12 and up, TikTok's buttons and red text take the color of your wallpaper theme and change on their own when you change the wallpaper, with no new patch needed. Android 11 and older keep TikTok's red, and the color you pick above isn't used while the option is on (#163).
* **TikTok:** A new Full-width progress bar switch sits under Show the time beside the progress bar in Playback. It takes away the gap TikTok leaves at each end, so the bar runs from one screen edge to the other and dragging still seeks. Turning it off or pausing Hushfeed puts the bar back as TikTok built it (#173).
* **TikTok:** With Hide the Tako bubble on, tapping a caption (or its "more") no longer opens a sheet with "Discover more with Tako" and an "Ask Tako" box on some videos. The caption expands in place instead, the way it already did on videos without Tako content. Turn the switch off, or pause Hushfeed, and TikTok's sheet comes back (#162).
* **TikTok:** A new switch, Only on the sides of the video, sits right under Long press in the Gestures section of Feed screen. Pick any Long press choice other than TikTok default, turn it on, and the choice runs only on the left and right thirds of the video. A press in the middle opens TikTok's own menu again, with Download, Not interested, Speed and the rest. It's off by default (#136).
* **TikTok:** With Hide the comment bar on opened videos on, Share and its label no longer sit over the end of the progress bar and the time beside it. On a video with no music disc, Share is the lowest button on the right, and only the disc used to move up out of the bar's way. Now the whole row of buttons goes up when it needs to (#153).
* **TikTok:** The LIVE chat auto-translate preset in Feature Gate Lab now says what else it needs. Its Translate comments button only shows up with a Japanese SIM too, so the preview asks you to turn on Override SIM details and pick Japan, NTT DOCOMO before you restart TikTok (#98).
* **TikTok:** A recorded screen layout now lists a popup's own views even when the screen behind it is very busy, and it no longer spends its space on the next video waiting under the For You feed. Before, the export could run out of room before it reached a card on screen (#162).
* **TikTok:** Hushfeed settings can now follow TikTok in Simplified Chinese. The complete first translation covers the settings pages, dialogs and runtime messages. Traditional Chinese stays in English until it has its own translation, and corrections to this first pass are welcome.
* **TikTok:** Allow screenshots and Circle to Search now covers popups that TikTok builds outside an Activity window. A controlled Android 15 check of TikTok's own Compose popup showed black with the switch off, visible content with it on, and black again while Hushfeed was paused. Naturally served popups still depend on what TikTok enables for an account.
* **TikTok:** Starting with the next release, Morphe Manager shows that release's whole change list even while it is the newest one. Before, Manager showed only the short bundle summary for the release on a phone, then showed its full list after another release came out. The published 0.74.0 index stays unchanged.
* **TikTok:** The current TikTok 47.1.4 build passed strict Android 17 16 KB-page runtime checks. It cold-started twice and played more than one feed video with compatibility mode disabled on the 16 KB emulator. The target-37 audit also covers the reflection used by Block this commenter. Static alignment results remain separate and don't claim that every bundled 32-bit file is compatible.
* **TikTok:** The Profile tab opens again on phones where it crashed or came up blank since 0.72.0. Two of Show follow status's hooks left a value typed two ways where their paths met, and some phones' Android refused the whole profile name class for it (#165, #140).
* **TikTok:** A Dark or Black caption background keeps the rounded corners TikTok gives the caption. Hushfeed used to put a plain color in place of TikTok's rounded shape, so the corners came out square. The color now fills TikTok's own shape (#158).
* **TikTok:** Save a profile picture on a long press works again. On TikTok 47.1.4 every hold on a profile picture said "Open the profile again and try once more", because Hushfeed looked for the profile under a name an older TikTok gave it. It now finds the profile the picture belongs to however TikTok names it, and still checks it against the @name on screen before saving (#160).
* **TikTok:** With Hide search suggestions above comments on, comments no longer open with an empty white band above the comment count on some videos. On some accounts TikTok saves the banner's spot before it builds the banner, so hiding only the banner left its spot behind. The switch now answers TikTok's own "any suggestion here?" check, so no spot is saved, and the newer style of that banner goes too (#161).
* **TikTok:** A comment's menu can now block the person who wrote it. Press and hold a comment and pick Block this commenter, and their comments leave the list right away, the way they do after a block from their profile. A banner names who you blocked and offers Undo. Turn on Block from a comment's menu in Hushfeed settings > Comments, under Blocking (#169).
* **TikTok:** With See-through status bar on, every feed video now reaches up under the clock, not only the first one after TikTok starts. TikTok plays that first video in a surface it makes at launch, and later videos can play in another kind of player with no surface inside it, so they kept a black band above and below. That player grows now too. With Fit the video to the screen also on, Fit wins and the whole picture stays in view instead of being grown back over its sides. Fill the screen with the video already covers the screen, so it and See-through status bar don't undo each other (#164).
* **TikTok:** Hide the unread badges on the bottom tabs also keeps down the bubble TikTok floats over Inbox when it opens, the one counting new likes, followers, comments and who's LIVE. It used to take only the red counts and dots off the tabs, so that bubble still came up every time (#170).
* **TikTok:** Saving a big pick from a profile grid no longer gives up a few videos in with "the rest ran out of time". The whole save had two minutes for all its videos, so a pick of 50 always ran out. Each video now gets its own two minutes, and one that really stalls still stops the save (#27).
* **TikTok:** Hide suggestions on the search page also hides the "You may like" list on accounts where TikTok 47.1.4 draws that list itself. On those accounts the list and its Refresh button stayed on the search page with the switch on, because none of the switch's hooks reached it. Your search history still shows, and turning the switch off brings the list back.

## 0.74.0 (2026-10-14)

* **TikTok:** LIVE controls can keep the LIVE quality you picked after TikTok restarts. TikTok forgets the pick whenever it closes, so the first rooms after a restart went back to its own choice. Turn on Keep the LIVE quality after TikTok restarts, under Offer LIVE quality in more rooms in Hushfeed settings > Playback. A room whose stream doesn't have that quality still opens at TikTok's choice. A pick from the Video quality list in a room's Share menu now carries on to the next room too (#57).

* **TikTok:** With Hide the comment bar on opened videos on, a video from someone you follow who follows you back no longer has TikTok's Message bar sitting over the bottom of the caption, the progress bar and the buttons on the right. TikTok shows that bar there instead of Add comment, and the patch gave the video the bar's room without taking the bar away. It goes with the comment bar now (#153).

* **TikTok:** Accent color now reaches the plus under the creator's avatar on the feed rail too. That plus is a picture with TikTok's red drawn into it, so it stayed red while the buttons and text took your color. Its red and the soft edge around the white cross now follow your color, and the cross stays white (#84).

* **TikTok:** With Hide the comment bar on opened videos on, the music disc at the bottom right no longer sits on the end of the progress bar or on the time beside it. A short caption used to leave it there. It now stays above the bar, the way it does with the comment bar showing (#153).

* **TikTok:** AMOLED dark theme now reaches the cards on the Settings and privacy list itself. That list takes its colors from a palette in TikTok's code instead of its resources, so with TikTok's Display set to Dark its cards stayed gray after 0.73.0. They take the AMOLED color now. Light mode, TikTok's high contrast palette and the profile menu's cards keep their own colors (#152).

* **TikTok:** A new Show translation options in Language settings switch, under Translate button in comments, brings back the translation rows (Always translate, Translate into, Don't translate) on TikTok's own Language page under Settings and privacy. TikTok hides that whole group for some accounts through two of its experiments, which is why it showed while logged out and went away after login. The switch answers those two on that page only, so the feed keeps TikTok's own translation behavior (#143).

* **TikTok:** Hushfeed can patch PineDrama 47.1.4 now, TikTok's app for drama series (`com.ss.android.ttmd.video`). It's built from TikTok's own code, so 90 of the 134 patches work on it and Morphe Manager offers just those. The other 44 rely on parts PineDrama changed or doesn't have, like LIVE, the inbox rows and some of the feed buttons. On PineDrama, the Look like the store app patch answers with PineDrama's own signature rather than TikTok's (#156).

* **TikTok:** The LIVE Record button no longer covers the avatars of the Discover panel when that opens over a room. It moves down below them and goes back once the panel closes (#37).

* **TikTok:** A new Floating bottom bar switch, under See-through bottom bar, puts the tabs on a rounded, see-through dark pill that floats over the video, a little in from the sides and bottom. The tabs stay exactly where they were and work as before (#124).

* **TikTok:** A new See-through status bar switch on the Feed screen page lets the main feed's video reach the top edge, with the clock and status icons over it instead of a black band. A tall video grows a little to fill the screen top to bottom, so a sliver comes off each side. Wide videos keep their bars. The tabs at the top stay where they are, and videos you open from a profile or search are left as they were (#145).

* **TikTok:** With Show a LIVE under the status bar on, the black strip under the clock no longer flashes for a moment as a LIVE room opens or as you swipe to the next room. The same goes for Hide the status bar in LIVE (#137).

* **TikTok:** Two new switches under Profile, Hide profile views and Hide Add friends, take those buttons off the top of your Profile tab. The menu button keeps its place, and another person's profile is left as it is (#158).

* **TikTok:** A LIVE recording that TikTok closed on (swiped out of recents, or stopped by the phone) is saved at the next start, with the same "TikTok closed" message a recording cut short gets. It used to sit in the cache, unsaved, until the day-old cleanup threw it away (#37).

* **TikTok:** A new Also on opened videos switch, under Hide the search button on the feed, hides the search bar at the top of videos you open from search, from a profile's posts, Favorites or Likes, or from your watch history. The Back button stays where it is (#158).

* **TikTok:** A new Normal-weight captions switch, under Caption text size, draws TikTok's spoken captions at regular weight instead of bold. It keeps TikTok's own font and works with any caption size (#158).

* **TikTok:** A new Full long-press menu in search results switch, under Full long-press menu on a creator's videos, does the same for search. A long press on a video you opened from search opens the full For You menu instead of the small share sheet. Like the creator one, it needs Long press on TikTok default (#120).

* **TikTok:** A new Show the country as a flag switch, under Show where a video was posted, shows the country's flag in place of its two letters. It changes both places Hushfeed shows a country: by the creator's name, and after a commenter's time (#157).

* **TikTok:** Don't start the first video stops that video at its first frame and keeps it stopped until you tap. It used to play for a second or two before the pause caught up with it, since TikTok's first report that it's playing comes that late, and on a cold start TikTok started it once more a moment after the pause (#83).

* **TikTok:** A new Accent color patch swaps the red of TikTok's main buttons and red text for blue, green, purple, orange or teal, or for a color code you type. You pick it in the patch's options when you patch. TikTok's lighter and darker reds follow your color, so text stays readable in dark and light mode, and warnings stay red. Like AMOLED dark theme it's built into the app, so only patching again without it takes it back (#84).

## 0.73.0 (2026-10-14)

* **TikTok:** A profile background saved with the Profile background switch on no longer disappears after TikTok restarts. TikTok asks its servers for profiles with a value that left the background out, and the switch now sends the one that brings it back, the same one Feature Gate Lab's Profile banner preset sets. Flipping the switch now offers a restart (#80).

* **TikTok:** Feed filter has a Minimum video length beside the maximum, under Limits. Videos shorter than it are hidden, while photo posts and videos TikTok sent no length for stay. Like the maximum, when a whole batch would go, the video closest to your limit is kept (#154).

* **TikTok:** Saving a story on Automatic quality now gets its tallest version that other players can open, not the smaller one TikTok's player happened to be streaming. A story whose own clean file is already that tall keeps it, and one with nothing better saves the way it did before (#155).

* **TikTok:** Turning a commenter switch on or off under Hushfeed settings > Comments now changes the comments of a video you'd already opened. Before, reopening those comments showed the names and countries they were first drawn with until you moved to another video or restarted TikTok. A verified badge after a name stays where it is.

* **TikTok:** A new Color commenter names switch under Hushfeed settings > Comments gives each commenter's name a color of its own. An account gets the same one every time it shows up, a darker shade on TikTok's light comment panel and a lighter one on its dark panel, so a long thread is easier to follow. It starts off.

* **TikTok:** Hide videos you have already seen works on the Following tab too. On TikTok 47.1.4 that tab only meets the feed rules where TikTok reads its list, and the switch was left off there. A video you watch on the tab stays put while more load under it, and one you saw earlier is taken out when it turns up.

* **TikTok:** A video you're watching on For You or Following stays put when TikTok reads the list again. It used to vanish from under you once it got older than your Maximum post age, once Hide videos you have already seen counted it as watched, or when its counts moved past a range or views per like limit you set. Videos that come in after it still go through every rule. Turning a rule on or changing one checks them all again, though Hide videos you have already seen still leaves alone the ones you watched in that list.

* **TikTok:** Show the time to the second has two options under it. Show the time zone (on, so the row reads as before) can drop the zone, and Short date writes the date the short way your language does, like 10/7/25. Each works without the other, the seconds always stay, and both use your language's own formats and your phone's 12 or 24 hour clock (#147).

* **TikTok:** Pick videos to save from profile grids works inside a Favorites collection folder too. Its videos get the same circle, in the top left corner there since TikTok's own Select posts box takes the top right. Pick all reads the folder's list, so a whole collection saves in runs of 50 like a Liked or Favorites list (#27).

* **TikTok:** When some of the original photos you save don't come through, the result now has Retry beside Open. It tries again only the photos that failed, in the post's order and under the same file names, so the ones that landed aren't saved a second time. Photos you cancelled or didn't pick are left out, and Retry keeps showing up as long as some still fail. A Retry made under another account than the one you're on now says so instead of saving. Tap Retry while that post is still saving and it says so, and it stays there to tap again once the save is done.

* **TikTok:** Quick setups can be hidden from the settings home with a long press on its card, and its help line now says so. The **Quick setups on the home page** row on Backup and restore brings it back. Settings backups and the Quick setups choices leave it alone.

* **TikTok:** LIVE rooms can show a Record button, off by default and found in Playback settings. It saves what you watch from the moment you tap it until you tap Stop or leave the room. Nothing from before the tap is saved. If the LIVE ends, the connection drops or the phone runs low on room, you get what was recorded so far and a message that says so (#37).

* **TikTok:** With Hide the comment bar on opened videos on, the progress bar no longer runs across the last line of the caption on a video you open from a profile, your likes or search. The caption and the music disc beside it move up just enough for the bar, which runs along the caption's bottom edge the way it does with the comment bar showing (#153).

* **TikTok:** The diagnostic report no longer says the overlay is missing something just because Hide the comment bar on opened videos is on while you're on the main feed, which never has that bar (#153).

* **TikTok:** The diagnostic report's storage section no longer shows part of your account ID as a folder name. TikTok names its offline videos folder after the account (#70).

* **TikTok:** With Hide the comment bar on opened videos on, photo posts in an opened grid no longer end above a black band at the bottom. A grid that starts on photos, or runs through several, has no comment bar until a video comes along, and the room kept for it came back as that band. Swiping to the next post in the grid no longer brings the band back either, and a video you open from search results now reaches the bottom too (#153).

* **TikTok:** With Show a LIVE under the status bar on, the panels above the first room of the LIVE feed (the one the LIVE button at the top left opens, scrolled up past the first room) now start below the clock. Their top row sat under the status bar, where it was hard to tap. The rooms themselves still reach up under the clock (#137).

* **TikTok:** A LIVE in the For You feed no longer carries over the Service+ row (the "Service+ | Ask me anything!" line under the name) from a LIVE you scrolled past. When the next LIVE has no Service+ row of its own, the leftover one is hidden. It's part of the LIVE controls patch and needs no switch (#94).

* **TikTok:** LIVE controls can add clear screen to every LIVE room's menu. Clear screen hides a room's chat, gifts and buttons so only the stream shows, and TikTok offered it in only some rooms. Turn on Offer clear screen in every LIVE room in Hushfeed settings > Playback (#57).

* **TikTok:** With Show where a video was posted or Show the handle instead of the name on, a creator's display name that comes back on screen is no longer left cut short beside an empty row. Putting the name back now lets the row measure it again (#127).

* **TikTok:** AMOLED dark theme now turns the cards on TikTok's own settings pages black as well, on the pages under Settings and privacy, like Display. TikTok's dark mode already paints those pages and search black, so the cards were the gray left over. The search field, the filter chips and the profile menu's cards keep their dark gray, so you can still see where to tap (#152).

* **TikTok:** Feature Gate Lab has two presets from TikTok's regional builds. Scrolling comments on videos turns on Korea's one-line comments that run across a video, landscape videos included. Several photos in one comment turns on Japan's photo picker that adds more than one photo to a comment. Both need the Lab's overrides switch and a TikTok restart, and Undo puts back the values you had. TikTok's servers still decide which videos carry scrolling comments and whether a comment with several photos posts (#98).

* **TikTok:** A new switch on the Feed screen page, See-through bottom bar, lets the video reach the bottom of the screen and takes the background off the tab bar so the tabs sit over the picture. It starts off (#145).

* **TikTok:** Feature Gate Lab has a new preset from Japan's builds, LIVE chat auto-translate. It turns on the Translate comments option under Share in a LIVE room. It needs the Lab's overrides switch and a TikTok restart, and Undo puts back the value you had (#98).

* **TikTok:** Advanced downloads can save several posts from a profile grid in one run. Turn on Pick videos to save from profile grids in Hushfeed settings > Downloads and every video in a profile, Liked or Favorites grid gets a circle. Tap the circles of up to 50 posts, then tap Save on the bar at the bottom. They're saved one after another with a running count and Cancel. A post that fails is skipped and the next one still saves, a photo post saves its photos at full size, and with Check for already-saved videos on a video saved before is counted and left alone. Stories and ads get no circle, and TikTok's own tap and long press on a cell work as before until something is picked (#27).

* **TikTok:** Pick videos to save from profile grids has a Pick all button on its bar. It picks every post the list has loaded, in the list's order and up to 50 at a time, leaving out stories, ads and, with Check for already-saved videos on, videos saved before. With that check on, a whole profile, Liked or Favorites list saves in runs: save these, tap Pick all again, and the next run starts where the last one stopped, even when TikTok closed partway through one. Only posts TikTok has already loaded are picked, so scroll to the end of a long list first (#27).

* **TikTok:** With Hide videos you have already seen on and a full record of 10,000 videos, each video you watch no longer goes through the whole record to find the oldest one to drop. One pass now lines up the 256 oldest, so it happens once every 256 videos, and a record loading at start trims once at the end instead of after every row. Which videos are kept doesn't change (#84).

## 0.72.0 (2026-10-13)

* **TikTok:** The diagnostic report has a new Changed settings section. It names every Hushfeed setting you've moved off its default, so a report shows which switches are on without anyone having to ask. Switches say on or off and numbers show their value. A setting you typed text into, like blocked words or a proxy address, is only named, never quoted (#149).

* **TikTok:** Added an Android 17 emulator setup note for SDK tools that misread minor API versions.

* **TikTok:** Every Hushfeed settings page now sits right on the main settings page, under short headings: Your feed, Conversations, Saving and sharing, Privacy and app, and Hushfeed. Nothing is tucked inside another menu any more, so any page is one tap away. The "changed" counts the group pages showed went with them. Pause Hushfeed, the full status card and Diagnostics are near the end under the Hushfeed heading, and tapping the status next to the title scrolls you there.

* **TikTok:** Skip content warnings has a new switch under Feed screen > Popups, Hide Check your sources warnings, off until you turn it on. It takes away the "Check your sources before sharing" banner TikTok lays across the top of some videos, and the prompt TikTok shows when you go to share one, so the share just goes through. It's its own switch because TikTok drives this banner from different data than the Check sources banner that Hide unverified content notices covers.

* **TikTok:** Settings search now lets you flip a switch right in the results, so you don't have to open its page first. Switches that do more than turn on or off, like Ghost mode or the screen time ones, still take you to their page.

* **TikTok:** Quick setups sits right under the search field on the main settings page. Quiet turns on the Calm feed switches (ads, Shop, LIVE and the rest), Privacy turns on Ghost mode and the tracking blocks that cost you nothing, and Back to defaults puts every switch back the way Hushfeed ships. Each one tells you how many settings it changed and offers Undo, and the Restart row shows up when a change needs it. Your screen time rules still apply: on a locked day Back to defaults leaves the budget switches alone, and with Wait a day to loosen on, turning them off waits until the day starts over. Undo takes that back too.

* **TikTok:** The main Hushfeed settings page ends with two new rows. Report a problem opens a GitHub bug report with your Hushfeed and TikTok versions, your phone and the switches you have on already filled in, so you only add what happened. With a long list of switches on, the report lists as many as GitHub takes in one link and says how many more there are. Support Hushfeed opens the Ko-fi page if you'd like to buy me a coffee.

* **TikTok:** Restarting TikTok from the Restart row or the banner now brings you back to the Hushfeed settings page you were on, scrolled to where you were, so you can keep going where you left off. Back still takes you to the main settings page.

* **TikTok:** Hushfeed settings has a new look throughout. The home page has pink icons and a clearer search field. Pages share rounded panels, quieter section headings and pill switches. Feature Gate Lab and the creator editor follow the same style, with large text and light mode kept readable.

* **TikTok:** Restored the profile-menu coverage check, the high-register hook fixture and the signing test project. The signing fixture also accepts D8 options in any order.

* **TikTok:** Press and hold For You at the top of the feed to open Hushfeed settings. TikTok's feed has no logo to hold, so For You is the shortcut. A quick tap on For You still does what it always did, and the long press works while Hushfeed is paused.

* **TikTok:** Downloads has a new switch under Video download quality, Ask for the quality on each save. With it on, Download lists the qualities the video comes in, with the size TikTok gives for each, and saves the one you pick. Automatic, TikTok's own file, is always first. The quality you set stays as it is for the next save.

* **TikTok:** A new switch next to Long press, Full long-press menu on a creator's videos, makes a long press on a video you opened from someone's profile open the same full menu as on For You instead of the small share sheet (#120).

* **TikTok:** A new switch on the Share sheet page, Copy the link with one press on Share, makes a press on Share under a video copy that video's link, cleaned the way a shared link is. A second press right after opens the share sheet as usual.

* **TikTok:** Silence the feed while comments are open is now called Pause the video while comments are open, which says what it does.

* **TikTok:** With Automatic Clear display set to no delay, a video you open from search, a profile, a hashtag or a sound now starts with its controls already hidden, also after you brought them back on the feed to get to search. Before, its buttons and the search bar at the top showed for about half a second (#84).

* **TikTok:** Block this creator and Save cover now sit in the same gray circle as TikTok's own buttons in the share sheet, with an icon the size and color of theirs in light and dark mode. They also get their icon in the panel that opens when you press and hold a feed video, where it was missing.

* **TikTok:** Double tap has a new choice, Speed up this video, double tap again to undo. The video on screen plays at your Speed while you hold the video (2x unless you picked another), a second double tap puts back the speed it had, and the next video plays at its usual speed (#136).

* **TikTok:** Downloads has a new switch, Save cover button in the share sheet. With it on, a video's share sheet shows Save cover right after Download, so you save the cover only when you want it, and Download saves just the video. While it's off, Save the cover as well works as before (#146).

* **TikTok:** Search loaded activity also steps aside when it runs into a problem while TikTok loads more of the list: the search closes and TikTok's request goes ahead instead of the app closing.

* **TikTok:** AMOLED dark theme now also turns black the panel that opens when you press and hold a feed video, and Inbox's Activity and New followers pages. They had kept TikTok's dark gray. The cards inside the panel stay gray so they still stand out.

* **TikTok:** Network proxy now says which connections skip it. Hushfeed's saves and the short-link lookup behind Copy link connect directly, after their own address checks, and the exported diagnostic report lists every route under Network routes.

* **TikTok:** On Android 6 through 8.1, screen readers now announce the page titles, section titles and recovery screen titles in Hushfeed settings as headings, the same as on newer Android.

* **TikTok:** Release publishing works under Windows PowerShell 5.1 as well as PowerShell 7. The duplicate-tag check now reads each release on a page under 5.1, where it used to take the whole page for one release, and the publication and script checks pass in both shells.

* **TikTok:** Pause the video while comments are open now pauses the video as the comments open, also the first time after TikTok starts and on a video with no comments. Before, that first sheet kept playing until its comments had loaded. It also works now with Block author button left out of the patch selection.

* **TikTok:** Swipe for brightness and volume leaves the comment sheet alone: a drag near the edge while the comments are open scrolls them and doesn't change the brightness or volume.

* **TikTok:** Show commenter handle and region falls back to the country the feed showed for a commenter's account when the comment itself doesn't carry one, which TikTok often leaves out. It needs one of their videos to have passed through your feed.

* **TikTok:** If Search loaded activity runs into a problem inside one of TikTok's list updates, the search closes and TikTok's own list stays on screen instead of the app closing.

* **TikTok:** Release publishing now uploads and downloads assets through the exact release it checked instead of looking the release up by tag again, and stops when two releases share a tag. Rebuilding a tag that's already published is refused, and the index step compares the receipt with the hosted copy's checksum before copying it in.

* **TikTok:** New Show commenter handle and region patch: comments can show each commenter's @username instead of their display name, and the country their account is set to after the comment's time. Each has its own switch in Hushfeed settings > Comments, and both start off. Ported from na-x-ril's onlynazril patches.

* **TikTok:** Use system font can also show TikTok's text in a .ttf or .otf file you import. Turn on Use an imported font in App, pick the file and restart TikTok. If the file is removed or can't be read, the text goes back to what Use system font shows. Ported from na-x-ril's onlynazril patches.

* **TikTok:** New Profile background patch: a switch under Profile in App opens TikTok's own profile background editor in Edit profile and shows the backgrounds other profiles have set. TikTok's servers may still refuse to save one for an account TikTok hasn't offered it to. It starts off. Ported from na-x-ril's onlynazril patches.

* **TikTok:** Hide CAPTCHA popups is a switch again, in Hushfeed settings > Feed screen, and starts off. It skips the puzzle TikTok raises while you're just browsing. A puzzle right after you like, follow, comment or repost still shows, and so do sign-in, SMS and two-step checks. With Hide the risk control CAPTCHA patched in, that check's browsing puzzles are skipped too. The rule comes from na-x-ril's onlynazril patches.

* **TikTok:** With Block contact list access on, a contacts read that asks for every column now gets back an empty list that still has the contacts columns, so a TikTok screen that looks up a contact card's name doesn't trip over a missing column.

* **TikTok:** Prefer H.264 video and Play SDR instead of HDR now keep a video's other versions when none of the preferred ones has an address to play from, so Playback quality still picks among versions that play.

* **TikTok:** In Clear display, the bottom tabs stay hidden after you swipe over to a creator's profile and back (#84).

* **TikTok:** Numbers typed into Own profile counts stay in their fields when the screen rotates or the theme changes while the editor is open.

* **TikTok:** If the loaded Activity search fails on a TikTok build, its bar goes away and TikTok's own list stays on screen instead of the app closing. The search stays off until TikTok restarts.

* **TikTok:** A new switch under Fade the video controls, Keep faded controls in Clear display, is on by default. Turn it off and Clear display hides the buttons and caption again while the fade still works everywhere else (#84).

* **TikTok:** With Seek bar on video stories on, a story viewer you've closed doesn't stay in memory anymore while you keep using TikTok.

* **TikTok:** A creator's name on the author line no longer stays cut short after a quick scroll, or after a photo post's badge shrinks to an icon (#127).

* **TikTok:** Your profile can show a verified badge or hide its account warning on this phone. Other people see your real status.

* **TikTok:** Media checks now exercise the installed muxer with generated clips and verify playable output. Test files are removed after verification.

* **TikTok:** Switching Activity categories after a search no longer crashes the app. Search can start from the list already on screen.

* **TikTok:** Device checks now recognize popup menus through their app ownership. Other apps and unmatched windows still refuse input.

* **TikTok:** Your own profile can show counts you choose on this phone. Blank fields keep TikTok's counts. Other people see the real values.

* **TikTok:** Added optional search for loaded Activity notifications. Search pauses loading more, and clearing the query restores the current list and position.

* **TikTok:** Photo posts keep their X usable when the controls are faded in Clear display. Other faded controls still pass taps through to the video (#84).

* **TikTok:** Verified caption styling and empty feed handling on Android 6 in the small ART helper.

* **TikTok:** Verified brightness and volume drags over the faded comment rail. Device checks restore brightness, audio and temporary settings after each drag.

* **TikTok:** Gesture checks now refuse input when a muted stream hides its volume and reject gestures that finish after the deadline.

* **TikTok:** Device checks now support real two-finger gestures. Both finger positions on the rail were verified to exit Clear display (#84).

* **TikTok:** A hidden Clear display seekbar stays transparent after TikTok changes its opacity or replaces the row. Turning the hide setting off restores TikTok's latest opacity (#84).

* **TikTok:** Automatic Clear display tries again when you reopen the same video in a new viewer. A manual exit still lasts while you return from comments or another brief focus loss (#84).

* **TikTok:** Verified that releasing or expiring a session hold keeps video paused until another app returns audio focus.

* **TikTok:** Local development index previews check the bundle's actual version and Manager's main/dev ordering. They leave the stable index untouched and don't publish anything.

* **TikTok:** Hide root keeps root status and root-tool details out of TikTok's device checks. It starts off. Restart TikTok after changing it.

* **TikTok:** Block this creator under Share sheet adds a Block action beside Report. It starts off and offers Undo after TikTok confirms. An older Undo waits for a pending block of the same creator.

* **TikTok:** Received DM stickers open a preview with Save media. Saving uses the chosen sticker format and checks encrypted files before writing them. Opening the preview sends no message.

* **TikTok:** Release uploads now stay in a draft until the bundle, receipt and checksums have been downloaded and verified. Interrupted uploads resume only after existing assets match. An optional immutable check requires GitHub's repository setting and verifies the published attestation.

* **TikTok:** Verified that TalkBack announces a settings notice's action as Double tap to close.

* **TikTok:** Clear all is visible beside Suggested accounts in the Inbox again. TikTok's full-width title wrapper now leaves room for a button with a full-size touch target.

* **TikTok:** What's new now shows a concise summary of each change in the settings language. Full changelog opens the complete release history. Later and Got it keep their existing behavior, and the actions wrap on narrow screens or with larger text.

* **TikTok:** Feature Gate Lab has a New comment emoji preset. It enables the native comment emoji experiment while keeping TikTok's catalog availability and fallback checks. Restart TikTok after applying it. Assets and server eligibility still determine what appears.

* **TikTok:** Feature Gate Lab has a More hashtags per post preset. It turns off the client hashtag cap without changing the maximum or unrelated caption checks. Restart TikTok after applying it. TikTok may still enforce a server limit.

* **TikTok:** A new APK inspector checks native library load alignment and stored ZIP offsets for 16 KB pages. It reports failures per library without extracting files. Android runtime compatibility still needs a separate device check.

* **TikTok:** Settings tables can be exported as XML, translated and imported before regeneration. The exchange preserves plural forms and format placeholders, and rejects changed keys or incomplete translations before replacing a table.

* **TikTok:** Fixture checks no longer print duplicate job-removal errors when patching finishes during output collection. Failed applications still reject the push.

* **TikTok:** A sticker preview gets its Save action when TikTok adds the native actions late, replaces their row or removes the Save button. Reusing the preview still saves the sticker currently shown.

* **TikTok:** Turning off Hide suggested accounts restores a suggestion cell's original spacing, including negative margins.

* **TikTok:** The original reporter confirmed that the post date follows Author text size (#66).

* **TikTok:** A restored backup with a filename pattern full of dots, or a download folder full of slashes, no longer stalls the settings page. Trimming them took longer the longer the text got, and at a million characters that meant over a minute of a frozen screen.

* **TikTok:** The Back arrow on a settings page opened from a feed filter notice's Show the switch now leaves settings. It used to do nothing there, so only the phone's own Back got you out.

* **TikTok:** Hide a VPN connection now says what it really does. Android stops reporting the VPN to TikTok and its tunnel is left off the network list, but TikTok can still see the VPN's IP address, so the old "TikTok can't tell" promised too much. Block the advertising ID now compares the blank ID to deleting yours, which is what Android does, not resetting it.

* **TikTok:** Forget saved videos, its Undo and Add apps to Share via no longer get stuck when TikTok's background queue is full. The row stayed greyed out, or the app list kept saying Looking for apps, until TikTok restarted. Now they tell you and you can try again. The app list also says when it couldn't look, instead of saying no app takes shared text.

* **TikTok:** verify-all-patches.ps1 checks the -KeepIn folder before it empties it. A mistyped path used to be deleted whole, whatever it named. Now it refuses a drive root, your profile, the repository, any folder that holds an input, a path through a junction, and a folder with files an earlier run didn't leave there.

* **TikTok:** TalkBack can reach Hushfeed settings from For You now. TalkBack turns a press into a tap the shortcut never sees, so For You lists an Open Hushfeed settings action in TalkBack's actions instead. TikTok's own tap and what TalkBack reads for the tab are unchanged.

* **TikTok:** Importing a new font no longer loses the one you had when the save fails or TikTok closes halfway through. The earlier font waits under another name until the new one is in place, and comes back if it never gets there.

* **TikTok:** With Hide seen videos on, switching accounts while a video plays can no longer hide that video for the account you switched to. The watch stays with the account that watched it, and the next account still marks the same video when it plays it.

* **TikTok:** Rotating the phone, resizing the window or switching dark mode while an editor is open no longer throws away what you picked or typed in it. The share action lists, Add apps to Share via, Hide profile shortcuts, the popup list, Locally hidden creators and the count ranges keep your unsaved choices, and Cancel still changes nothing.

* **TikTok:** Icon picture now checks the picture's pixel data too, not just its header and checksums. A PNG whose image data is cut short or damaged, or whose palette breaks the PNG rules, stops patching with the reason before anything in TikTok changes, instead of being copied into the app as it is.

* **TikTok:** With Show a LIVE under the status bar on, every room you scroll to reaches up under the clock with the shade behind it. Some rooms kept a black band there, and after the first few LIVEs the strip could go light again (#137).

* **TikTok:** With Show a LIVE under the status bar on, Clear display in a LIVE no longer leaves a dark strip under the clock, and the top row of the side panel you open by swiping left stays below the clock (#137).

* **TikTok:** Hide the Tako bubble now also hides the Tako bubble in the bottom right corner of feed videos, which some accounts get instead of the one above the profile button (#115).

* **TikTok:** The Feature Gate Lab has a new Profile banner preset. It turns on Add background in TikTok's Edit profile without switching the rest of the profile to the new layout (#80).

* **TikTok:** The collections popup you get by holding the Favorites button is dark now when TikTok is in dark mode, instead of white. Dark Favorites popup in Hushfeed settings > App turns it off (#142).

## 0.71.2 (2026-10-11)

* **TikTok:** Hide the status bar in LIVE rooms and Show a LIVE under the status bar now work on a LIVE opened from the preview under a creator's card in search too. TikTok opens that LIVE inside the page it uses for opened videos, which the switches didn't reach (#137).

## 0.71.1 (2026-10-11)

* **TikTok:** With Keep captions in Clear display on, the caption in Clear display now sits where TikTok showed it before the controls went away, instead of dropping to the bottom edge (#141). Before any caption has shown, it keeps the old spot.

* **TikTok:** With Show a LIVE under the status bar on, the strip under the clock is shaded like the rest of the room's top again. On phones where TikTok keeps a black strip for the bar, it showed lighter than the stream below it (#137).

## 0.71.0 (2026-10-11)

* **TikTok:** With Double tap set to one of Hushfeed's actions and Long-press controls patched in, a second tap your finger stays on a moment too long no longer brings up TikTok's long press menu as well, so the share panel stops opening on top of the comments. (#63)

* **TikTok:** LIVE controls has a third switch, Offer LIVE quality in more rooms, under Hushfeed settings > Playback. TikTok offers Video quality in a LIVE's menu only when the stream asks for it or TikTok turns its newer quality picker on for your account, so in some rooms there was no way to pick one. With the switch on, most rooms without it get the newer picker (audio-only rooms still don't), and the next room opens at the quality you picked last until TikTok closes. It's off by default. (#57)

* **TikTok:** Show dates on profile grids and Show engagement rate work on TikTok 47.1.4's grids now, on a profile's videos and on Favorites and liked videos too. That version sets each cell's view count in a place the patch didn't reach, so neither the date nor the rate showed up there.

* **TikTok:** Blocked creators, blocked words, the view and like ranges and switches like Hide LIVE videos and Hide stories now work on the Following tab too. On TikTok 47.1.4 that tab only lost its ads and place-tagged videos, so a creator you'd blocked could still turn up there.

* **TikTok:** A word that isn't a language code in Don't auto translate these languages now gets a plain "isn't a language code" message. It used to warn that no video would ever match, which is the caption language filter's warning and doesn't fit a list that only keeps text untranslated (#121).

* **TikTok:** Build details now also names the patches that only change TikTok's resources, like Change app name, Custom launcher icon and Trust user certificates. It lists the patch, never the name or picture you picked in its options.

* **TikTok:** With Log diagnostics on, the log now says once per video how many progress bar preview pictures TikTok sent with it. If the preview doesn't show while you drag the bar, a diagnostics export tells whether the video came with any (#140).

* **TikTok:** Build details and the full log export now list every patch that changed TikTok's code in your build, by a short name, including the ones with no switch like Comment send fix and Network request report.

* **TikTok:** Clear display, remembered or automatic, now also clears a video you open from search. TikTok hands that video to the opened page already playing, and Clear display waited for a start that never came there, so the buttons and TikTok's search bar stayed up (#84).

* **TikTok:** Hide video overlays and Feed text sizes now also work when a link starts TikTok straight into search or a video, with no For You feed behind it. Before, the videos opened that way kept every overlay and TikTok's own text size (#84).

* **TikTok:** Protect external pages in TikTok's browser now counts the puzzle CAPTCHA that LIVEs and the Shop open as one of TikTok's own pages, so the puzzle keeps its link back to TikTok (#87). It loads from TikTok's own servers, just not from an address the switch knew.

* **TikTok:** A quick second tap on Build details, Hook status, Licenses or What's new no longer opens the same window twice, one on top of the other.

* **TikTok:** Like the video, as a Double tap or Long press action, now stays with the video you're on in every kind of feed. Where that video has no heart to press, it tells you instead of liking the one next to it.

* **TikTok:** A Double tap set to one of Hushfeed's actions now runs it once per tap, even if TikTok passes the same tap to two of its handlers.

* **TikTok:** Patching a TikTok version Hushfeed doesn't list is safer. The extras of Show the progress bar and the comment translate button are now left out on their own when they don't fit that version, so they can't stop the patch or go in halfway.

* **TikTok:** The Keep captions in Clear display and Automatic Clear display rows now spell Clear display the way TikTok does.

* **TikTok:** With Hide the comment bar on opened videos on, the progress bar on a long video you open from search or a profile now sits under the caption again. Before, it drew across the creator's name (#50).

* **TikTok:** Fade the video controls now also fades the progress bar on a paused video, the Following tab's stories pill, and on a video opened from search its top search bar, Full screen button and Add comment bar. Before, those stayed at full strength (#84).

* **TikTok:** A story can now be saved from its share sheet. Turn on Save a story from its share sheet under Downloads and a save button shows up there. It saves the copy without the watermark when TikTok has one, and tells you when only the watermarked copy is there (#93).

* **TikTok:** Show the progress bar has a new switch under Playback, Seek bar on video stories, which starts off. It puts a thin line along the bottom of a video story that you can drag to go back or skip ahead, with the time shown while you drag. Tapping still moves between stories (#72).

* **TikTok:** Keep playing in the background can now start a video over when it ends, so you don't have to press Play in the notification each time. Turn on Replay in the background under Playback. A call or another app's sound still pauses it (#99).

* **TikTok:** The music disc can spin again. TikTok 47.1.4 keeps it still out of the box, so Feed screen now has Spin the music disc, and Keep the music disc still if you'd rather it never turns. Both need a restart (#68).

* **TikTok:** Show the progress bar has two new switches under Playback, both off at first. Show the time beside the progress bar adds the time played and the video's length at the end of the bar, and Bigger progress bar handle draws a larger handle on a slightly thicker line (#90).

* **TikTok:** With Hide the Clear display controls on, TikTok's loading line, the thin bar that grows from the middle along the bottom of a video while it buffers, now goes too while the screen is cleared (#84).

* **TikTok:** A new Translate button in comments switch under Comments shows TikTok's own translate button next to the comment count. One tap translates every comment and reply, and another brings the originals back (#85). It starts off.

* **TikTok:** Clear display no longer leaves a thin line across the bottom of each new video for a few seconds (#84). It was the hairline along the top of the tab bar, which stayed behind when the tabs went away.

* **TikTok:** Hide the status bar in LIVE rooms keeps the black strip at the top away for the whole LIVE (#137). It used to give up after TikTok put the strip back a few times, like when panels open and close over the stream. A new Show a LIVE under the status bar switch lets a LIVE reach the top edge with the clock and icons over it, if you'd rather keep the bar. It starts off.

* **TikTok:** Double tap can now do anything Long press does, like copying the link or saving the frame, and Long press has a new Like the video choice (#136). A like from a long press works like TikTok's own and never takes a like away.

* **TikTok:** A collection or another grid you open a video from keeps every post in it, so Hide photo posts, blocked words and the other feed filters no longer make it play the wrong video (#135). Only ads are taken out of a list you opened.

* **TikTok:** Hide the Send to row no longer takes Report, Download and the rest of the actions row with it on the small share sheet a long press opens (#120). On that sheet TikTok puts both rows in one frame, and the whole frame was being hidden.

* **TikTok:** Show the progress bar shows the bar on the For You feed again, short videos included (#134). On 47.1.4 it was switching on the wrong TikTok check, one that decides whether a video is an ad, so the bar never came back while a video played.

* **TikTok:** The diagnostic report has a new Storage section listing the biggest folders in TikTok's storage, so a report can show what's filling it up (#70). It names folders only, never files, and hides account numbers.

* **TikTok:** Pressing back on a cleared video, or opening the share panel from it, no longer counts as turning Clear display off. Opening the same video again clears it like any other, and a remembered Clear display stays on (#84).

* **TikTok:** Remove LIVE extras now says it takes out the files for joining someone's LIVE as a guest, so joining by voice or video may stop working with it in (#132). Leave it out if you join LIVEs.

* **TikTok:** When Clear display is remembered, or Automatic Clear display has no delay, TikTok no longer shows the tabs under its loading spinner and the first video's buttons for about a second after a cold start. A video opened from Favorites or a profile starts cleared the same way (#84).

* **TikTok:** With Show where a video was posted on, the creator's name no longer gets cut short to fit the country when the row has room for both, so "Rn D… · TR" reads in full again (#127). A name that's really too long is still shortened so the country stays whole.

* **TikTok:** Hide already seen videos no longer takes videos out of a collection you open, so tapping a saved video plays that video again instead of the wrong one or nothing (#135). It still hides seen videos in For You, Following and Friends.

* **TikTok:** A new patch, Keep the app language, adds a Keep the language picked in TikTok switch under System in App (#61). When TikTok starts and decides the phone's language changed, it drops the language you picked in its own settings and follows the phone. With the switch on, your pick stays. It starts off.

* **TikTok:** A new Write the username and caption on the video switch in Downloads draws the @username and the first line of the caption in the bottom left corner of videos Hushfeed saves. The phone encodes the video again for it, so those saves take longer and show their progress with a Cancel button. It starts off.

* **TikTok:** Remove creation tools now warns that opening a creation tool, like effect search on the camera, can close TikTok (#133). Leave it out if you make videos.

* **TikTok:** Drop the animated image cache no longer swaps TikTok's frame cache for one that holds a single frame, which could leave animated stickers in comments stuck on their first frame (#130). Its switch is now called Don't decode animated frames ahead, and turning it on only stops TikTok from decoding frames before they're shown.

* **TikTok:** The Following stories bubble at the top of the feed comes back when Clear display ends. TikTok hides it as Clear display starts and used to leave it gone until the feed reloaded. It only comes back if it was showing before and still has someone in it.

* **TikTok:** A new Translate into row in Comments takes one language code, like en or es. Comments and captions TikTok translates come out in that language instead of the app's, so you can keep TikTok in one language and read translations in another. Leave it empty to keep TikTok's choice.

* **TikTok:** A new Burn-in guard choice under Clear display dims the buttons, caption and tabs over the video to a quarter after 5 seconds without a touch, and your next touch brings them back. With Dim when idle and shift, everything on the feed also moves a few pixels every 2 minutes, so an OLED screen doesn't wear in under controls that never move.

* **TikTok:** Hide the comment bar on opened videos now also takes the bar off a photo post you open from search. Before, the Add comment bar stayed on those.

* **TikTok:** A new Sound format choice in Downloads lets Save the sound as well and the Save the original sound long press make Opus files (.ogg). They're smaller than TikTok's M4A, and your phone encodes the sound again to make them. It needs Android 10 or later. As TikTok sent it stays the default, so those saves come out the same as before.

* **TikTok:** Fade the video controls now works with Clear display. Set between 1 and 99, Clear display keeps the buttons and caption on screen at the level you picked instead of taking them away, and a tap on them goes to the video, so nothing opens by accident. Press and hold or pinch still takes you out of Clear display, and outside it faded controls take taps as before (#84).

* **TikTok:** Mute feed videos keeps the feed quiet after you switch TikTok between light and dark. Before, the next videos could play with sound until you left the feed and came back.

* **TikTok:** Show how many were filtered and the switches that hide parts of the Inbox keep working after you switch TikTok between light and dark. Before, they could stop until TikTok was restarted.

* **TikTok:** Long-press Home for Hushfeed settings keeps working after you switch TikTok between light and dark. Before, it stopped until TikTok was restarted.

* **TikTok:** Restoring a backup no longer turns on the Network proxy or replaces the proxy address you already have, so a backup someone else made can't send your TikTok traffic through their server. On a phone with no proxy set up yet, the address comes along with the switch off, and a note after the restore says so.

* **TikTok:** Don't auto translate these languages now keeps Traditional Chinese as it was written when you type zh-Hant, zh-TW, zh-HK or zh-MO, while zh-Hans-TW still means Simplified. Before, those entries protected Simplified Chinese instead (#121).

* **TikTok:** Hide feed LIVE button still patches on a TikTok build where the side menu button can't be found. It skips that part, and the Hide the side menu button row only shows when the button was found (#128).

* **TikTok:** Settings search now finds Build details and What's new. A few results, like Feature Gate Lab and Licenses, now name the group they're in, and a search made only of punctuation says No matching settings.

* **TikTok:** The notice that shows at the bottom of Hushfeed settings now closes when you tap it, and the tap no longer lands on the setting under it. TalkBack reads its action as Close.

* **TikTok:** Pause now says in plain words what stays while it's on. Your settings stay saved, and so do changes made when you patched, like the app's name or icon. TikTok's Auto scroll is also written the same way on every row now.

* **TikTok:** The language lists in Feed filter and Comments now accept an extra comma, like en,,es. They used to refuse the list with a blank name in the message.

* **TikTok:** If copying the build details fails, the message now tells you to use Save build details instead.

* **TikTok:** What's new no longer shows stray backticks around setting names.

* **TikTok:** The time picker for When to send in Keep a streak going now opens dark or light to match Hushfeed settings.

* **TikTok:** Skip the splash ad, Skip update checks, Limit background traffic, Drop the animated image cache, Stop on-device AI profiling and Enable voice comments now each have a switch in Hushfeed settings, so Pause turns them off too and Morphe Manager's simple mode now picks them. Each of those switches starts off. If you patched with any of these before, turn its switch on after you update to keep it working. Skip the splash ad, Skip update checks, Limit background traffic and Don't decode animated frames ahead are under Performance in App. Stop on-device AI profiling is in Privacy and Allow voice comments is in Comments.

* **TikTok:** New patch, Skip passkey sign-in, in Morphe Manager's simple mode with its switch off (#102). Password managers won't hand a passkey to a patched app, so an account with a passkey could get stuck at TikTok's passkey step. Turn on Sign in without a passkey in Hushfeed settings > App and TikTok treats your phone as one without passkeys, so it offers its other ways to sign in, like your password or a code by email or text. If you're signed out, long-press Home to open Hushfeed settings.

## 0.70.0 (2026-10-09)

* **TikTok:** Author text size now sizes the post date next to the creator's name too, so the two stay the same size (#66).

* **TikTok:** Comments settings has a new row, Don't auto translate these languages. It's empty until you fill it in (#121). Type language codes like `es, de`, and when TikTok translates for you, captions and comments in those languages stay as they were written. See translation still works when you tap it, and TikTok's own Don't translate list isn't changed.

* **TikTok:** Keep offline videos until you delete them now covers two more ways TikTok cleared your list. With the switch on, TikTok's Auto adjust no longer lowers your offline videos limit or trims the list when TikTok starts, and the clean-up TikTok runs when its test of turning offline videos on by default ends is skipped (#123). Restart TikTok after you change the switch.

* **TikTok:** Restoring a settings backup made on an older TikTok build now brings back the Feature Gate Lab overrides that still fit this build. The ones that don't are kept but turned off.

* **TikTok:** Feed screen has a new switch, Hide the side menu button, off until you turn it on. It takes away the button at the top left of the feed, next to LIVE, that opens TikTok's side menu with Your orders, TikTok Minis and more. Restart TikTok after you change it (#128).

* **TikTok:** Every patch description in Morphe Manager is rewritten in plain English. Each one now says what the patch changes, why you might want it, and whether its switch starts on or off and where to find it in Hushfeed settings. A few patch options read more clearly too, and Skip push setup is now called Skip notification setup.

* **TikTok:** The rows in Hushfeed settings are reworded in plain English. Privacy, Comments, Downloads, Feed filter, Screen time, Region and other pages now say what each switch does and what you'll notice, without technical terms. A few titles changed too, like Stop TikTok's speed tests and Clean up shared links.

* **TikTok:** A handful of messages and status lines are plainer too, like the Ghost mode status, the Hook status explanation, the proxy password notice and the file name preview.

* **TikTok:** The Dark background color option in Morphe Manager now says "color code" instead of "hex code", and explains that black turns those pixels off completely on OLED screens.

* **TikTok:** The Hushfeed settings home now shows a compact status and search above seven groups. Related pages sit together, with backups, diagnostics and the Feature Gate Lab under App & advanced. About Hushfeed keeps build details and licenses in one place. Search still takes you straight to a setting, and your saved choices carry over. Pause now explains that saved settings and changes built into the APK remain.

* **TikTok:** Hushfeed now supports TikTok 47.1.4 only. If you're on 47.0.3 or 47.1.3, download the 47.1.4 APK from APKMirror and patch it in Morphe Manager with the same signing key, so your login and settings carry over. Feature Gate Lab keeps each override whose switch 47.1.4 still has and turns the rest off with its usual notice.

* **TikTok:** Custom offline videos limit has a new switch in Hushfeed settings > Downloads, Keep offline videos until you delete them. It's off until you turn it on (#123). TikTok throws out the videos it saved for offline viewing after a set time, sometimes only two days, and a list saved in one go disappears in one go, watched or not. With the switch on they stay until you delete them in TikTok's Offline videos settings, which is also how you get a fresh set. TikTok can still clear ones you've already watched when your phone runs low on space, and picking a lower limit still trims the list. Keep an eye on the storage line under the offline videos limit. The switch takes effect the next time TikTok starts.

* **TikTok:** Hide profile shortcuts has a new switch under App, Hide Thoughts on profiles, off until you turn it on (#122). It hides the Thoughts bubble TikTok puts above a profile picture, and on your own profile the prompt to share one. Restart TikTok after you change it.

* **TikTok:** Allow screenshots and Circle to Search now also takes the secure flag off popups and floating windows that set it in their own window settings, such as TikTok's Compose popups. They reach the screen without the window calls the patch already changed, so they could still show up black in screenshots and screen recordings. The idea comes from icysymmetra's TikTok patches.

* **TikTok:** Diagnostics has a new row, Capture the feed, for a feed problem that's easier to show than to describe. Tap it, scroll until the problem shows up, then come back and tap it again, and Hushfeed saves a text file to Download/Hushfeed. It has a line for every batch of videos the feed filters handled while it ran, with each video's verdict and the rule that hid it. Videos and creators appear only as short codes made fresh for each capture, and the file has no captions, names, handles or web addresses. Read it before you share it anyway. Nothing is recorded until you start a capture. The idea comes from icysymmetra's TikTok patches.

* **TikTok:** The Hide the Clear display controls description now mentions pinching the screen as another way to leave Clear display (#84).

* **TikTok:** A handful of switches now start off, like the rest of Hushfeed's switches, because their patches join the default selection. If you picked one of these patches before and want it to keep working, turn its switch back on after you update. Under Privacy that's Block contact list access, Block installed app scanning, Block location, Block clipboard reads, Block motion sensors, Don't save new searches and Show when the camera or microphone is in use. Under Comments it's Thumbs down blocks the commenter, Links in comments open and Hide comment popup ads, and under App it's Turn off haptics and Turn off screen transitions.

* **TikTok:** Morphe Manager's simple mode now picks 107 of Hushfeed's 130 patches, up from 55, so most of them no longer need Expert mode. Every switch the newly picked patches add starts off, and TikTok looks and works the way it ships until you turn something on in Hushfeed settings. Expert mode is still how you get the other 23, and the README's Patches section lists them with the reason each one stays out. Each patch's description now says where its switch is and that it starts off.

* **TikTok:** Morphe Manager has a new Interface group for the patches that change how TikTok looks. AMOLED dark theme, Use system font and Turn off screen transitions move there from Performance, Change app name, Custom launcher icon and Hide the launcher shortcuts from Settings, and Feed text sizes from Interaction.

## 0.69.0 (2026-10-08)

* **TikTok:** A TikTok video link you open now shows its video even when a feed filter would hide it, like Hide videos you have already seen for one you watched before (#117). TikTok loads a linked video as a feed page of its own, so filtering it out left TikTok's error screen there. Only that one video gets past the filters, and only for a minute after the link. Links to photo posts and the mobile site's older `m.tiktok.com/v/<id>.html` links count as one post too, here and for `Lock the feed`.

* **TikTok:** Leaving a LIVE room no longer closes TikTok when Remove LIVE extras is in your bundle (#119). The patch skipped a start-up step of the gift-effect widget that TikTok needs again when the room closes. It keeps that step now and still skips the widget's own setup, so animated gifts stay off.

* **TikTok:** Remove content credential and card scanner assets now empties Pitaya's Python runtime too, the five Python and NumPy libraries the Pitaya models ran on. Nothing else in TikTok loads them once the Pitaya feature is gone, which this patch already empties. The APK gets about 6.7 MB smaller than before, about 19 MB in all, and split bundles still match, since they carry the same files for arm64.

* **TikTok:** Skip content warnings has a second switch under Feed screen, Hide unverified content notices, off until you turn it on. It takes away the Check sources banner TikTok puts on a video it marks as unverified, and the warnings TikTok shows when you go to share one. Skipping the tap-through overlay still has its own switch.

* **TikTok:** New patch, Skip first-launch setup, off in the default selection. Pick it and a fresh install opens on the feed without TikTok's interest picker, the language and gender questions, the creators to follow, the swipe up tutorial or its own notification page. Consent, age and sign-in screens still show, and so does Android's own notification prompt. The switch, Skip TikTok's setup screens in Hushfeed settings > App, starts on, since the setup runs before you could reach it.

* **TikTok:** Block popups has a new switch under Feed screen, Hide TikTok's wind-down screens, off until you turn it on. It stops the bedtime wind-down, the breathing exercise and the daily limit screen TikTok puts over the feed. It only acts on an account TikTok's own settings say is an adult's. A teen's account keeps every one of them, and so does an account Family Pairing links to a parent. With Leave when TikTok says time is up on, the daily limit screen still comes up so that switch can act on it, and pausing Hushfeed brings them all back.

* **TikTok:** Hide video overlays has a new Hide Footnotes switch under Feed screen, and Hide inbox items has two more under Inbox, Hide Bulletin board and Hide the group chat prompt. They're all off until you turn them on. Hide Footnotes keeps TikTok's banner off videos that carry a note, Hide Bulletin board takes its rows out of your Inbox, and Hide the group chat prompt stops the banner at the top of the Inbox that invites you to start one.

* **TikTok:** Swipe for brightness and volume has two new rows under Feed screen > Gestures, Left edge strip and Right edge strip. Each one can be Brightness, Volume or Speed, and Speed is there when the Playback speed patch is in your bundle. Set to Speed, a drag along that edge speeds the video up or slows it down in steps of a quarter, from 0.5x to 3x, with the speed shown on screen as you go. The change is for that video only. The next video starts at your saved default, and nothing you've saved changes. The left edge is still brightness and the right is still volume until you change them, and a drag from anywhere else still scrolls the feed.

* **TikTok:** Feed tab navigation can now change the words under the bottom tab icons, from Hushfeed settings > Feed tabs. Hide the bottom tab names takes them away and leaves the icons. A name row for Home, Friends, Inbox, Profile and Shop puts a word of your own under that tab, and an empty row keeps TikTok's. Both apply right away, and clearing a name brings TikTok's back.

* **TikTok:** Hide inbox items has two more switches under Inbox > In a chat, both off until you turn them on. Turn off double tap to react stops a double tap on a message from putting a heart on it, and Turn off swipe to reply stops a sideways swipe from starting a reply. A single tap and a long press on a message work the same either way.

* **TikTok:** Run beside the store app now points a renamed copy's leftover uses of TikTok's package name at the copy. Its settings shared between TikTok's processes and the data it hands the live wallpaper service go to the copy itself, named however Clone app named it, instead of to the store app installed beside it. TikTok's checks for app-settings and Play links that name its own package now accept the copy's name as well as TikTok's. A copy that kept the store name, and a paused Hushfeed, behave as before.

* **TikTok:** Region spoof's Match region fields in requests now leaves sign-in requests alone. Signing in, signing up, account checks, token refreshes and logging out go out with your real region, including the system region and time zone TikTok saved as it started. The rest of TikTok's requests keep the preset. The switch's description in Hushfeed settings says so too.

* **TikTok:** Network proxy checks the proxy again when TikTok comes back to the screen, at most every five minutes, so a proxy that stops answering partway through a session gets one notice instead of none. Emptying the host or port field now saves, which leaves the proxy off, and Reset settings clears the proxy's user name and password as well. They never go into a file, so Undo leaves them empty.

* **TikTok:** New patch, Trust user certificates, left out unless you pick it. It lets TikTok trust certificate authorities you install in Android's settings as well as the system's, for looking at the app's traffic through a proxy like mitmproxy. Anyone who gets a certificate onto that phone could read TikTok's traffic too, so it's meant for a phone you test with.

* **TikTok:** Sanitize sharing links has a new switch in Hushfeed settings > Privacy, Copy the full link for short links, off by default. When Copy link gives you a short vt.tiktok.com or vm.tiktok.com link, Hushfeed opens it once in the background, reads where it leads and puts the full video link on your clipboard instead, with your other link settings applied. TikTok sees that open, just as it would when anyone taps the link. A link you send straight to another app goes out the way TikTok made it.

* **TikTok:** Copy bio and IDs has a new switch in Hushfeed settings > App, Account facts on profiles, off by default. A profile's share sheet then gets an Account facts button next to the copy buttons. It lists when the account joined, its region and language and when its username and display name last changed, and says so when the account is private or shows its liked videos, all from what TikTok already sent for the profile. Nothing extra is fetched, and a date, region or language the server left out says Not sent. Copy puts the whole list on the clipboard.

* **TikTok:** Feed filter has a new switch under Advanced, Show how many were filtered, off by default. It puts a small label under TikTok's top tabs that counts the posts the feed filter has taken out since TikTok started, ads and the rest alike. Tags and banners it hides inside a post don't add to it, and neither does a video TikTok tries again from its cache. It only shows on the Home feed once something's been taken out, and taps go through it to the video.

* **TikTok:** Feed filter has a new switch under Words, countries and languages, Match text stickers too, off by default. With it on, Blocked caption words also reads the text stickers on a video, the words a creator types over it in TikTok's editor, and hides the video when one matches, the same way a caption match does. Text that's part of the picture itself isn't there to read. If your filters ever hide everything TikTok just sent, the notice names these as Text sticker words.

* **TikTok:** Advanced downloads has a new switch under Video, Fall back to the watermarked copy, off by default. When the video without the watermark can't be fetched, Hushfeed saves TikTok's own watermarked copy instead of nothing and says so when it's done. That copy is the size TikTok's own save uses, so it can be smaller than the quality you picked. A cancel, or running out of space or time, still stops the save as before.

* **TikTok:** Advanced downloads has a new switch under Video, Mark saved videos, off by default. It puts a ✓ before the view count of every grid video Hushfeed has saved on this phone, so you can tell what you already have without opening it. A saved video in the feed gets the same mark before the time on its creator row, wherever Always show publish date shows that time. It goes by the record Check for already-saved videos keeps, so it works only while that switch is on, and photo posts never get the mark. A video you've deleted since keeps it until you use Forget saved videos. Pause Hushfeed turns it off.

* **TikTok:** Video covers can be saved. Advanced downloads has a new switch under Video, Save the cover as well, off by default. With it on, a Download also saves the video's cover at the largest size TikTok sends. It doesn't when Download hands the link to another app, or when you open a video you'd already saved instead of saving it again. Long press controls has a matching choice, Save the video's cover. Covers go to your photo folder, named after the video, and a HEIF or WebP cover comes out as a JPEG when Android can convert it.

* **TikTok:** Custom launcher icon has a new option, Icon picture. Give it the path of a square PNG from 432 to 1024 pixels a side and it puts your picture where TikTok's note was, on Android 8 and up. The icon style still picks the color behind it, and with a picture there's no themed version. If the file can't be used, patching stops and says why before anything in TikTok changes.

* **TikTok:** Advanced downloads has two new switches under Video, both off by default. Tag saved videos with their details writes the caption, creator, publication date and link into the saved MP4, where media players and ffprobe read them as the title, artist, date, comment and description. Save details as JSON makes the details file beside the video a JSON file instead of plain text. A video whose layout the tags can't be written into is still saved, just without them.

* **TikTok:** Always show publish date has two new switches under Feed screen > Video info, both off by default. Show the time to the second swaps TikTok's short date by the creator's name for the full date and time, seconds and your time zone included, on a 12 or 24 hour clock as your phone is set. Show dates on profile grids puts the day each video was posted under its view count, like Oct 7, with the year added for posts from another year. With Show engagement rate on too, the date sits on its own line under the count and the rate. A grid video TikTok sent without its time still gets a date, read from its ID, which carries the posting time.

* **TikTok:** Comment tools has another new switch, Hide comment box buttons, off by default. It takes the photo, @ and gift buttons out of the box where you write a comment. The emoji button stays, and typing and sending don't change. It's under Hushfeed settings > Comments, and Pause Hushfeed turns it off.

* **TikTok:** Comment tools has a new switch, Hide comment surveys, off by default. TikTok sometimes puts a question card in a comment list to ask what you think, and with the switch on the list is built the way it is for an account TikTok sends no survey to. It sits under Hushfeed settings > Comments, below Hide comment polls, and Pause Hushfeed turns it off.

* **TikTok:** Notification controls has a new switch under Inbox, Turn off push notifications, off by default. With it on, TikTok's push service isn't set up when the app starts, and nothing TikTok posts reaches the notification shade unless it's ongoing, like media controls. TikTok's wake locks are skipped as well, apart from the ones for a LIVE you're hosting and for background work that shows its own notification. You won't hear about new messages until you open TikTok, and a notification dropped while it's on doesn't come back. It takes effect after a restart, and turning it off or pausing Hushfeed sets push up again the next time TikTok starts. Unlike Limit background traffic's Skip push setup option, it doesn't need a repatch to undo.

* **TikTok:** Screen time has a new switch, Open shared videos alone, off by default. A link to one video, like one a friend sends you in another app, opens just that video, and the feed won't swipe past it, so there's no next video to fall into. It swipes again once another video plays, like after a refresh or a tap on Following, or when you come back to TikTok after 10 minutes or more away. Neither Auto-advance nor TikTok's own auto scroll moves on from it, and the other feed tabs, like Following or Friends, still swipe as usual. The row needs the Block author button and Feed tab navigation patches, and Pause Hushfeed turns it off.

* **TikTok:** Automatic video advance has a new switch under Playback, Auto-advance in search results, off by default. It answers yes to TikTok's own search auto scroll flag, which search results read instead of the For You one, so videos opened from search can move on at the end too. If you leave a search while it's scrolling, TikTok picks the scroll back up in your next search, and that one still stops at your Auto-advance limit and for Screen time like any other. Restart TikTok after turning it on.

* **TikTok:** Block popups' checklist now also lists the sheets and floating cards TikTok's server sends as campaigns, under the campaign's own name, so ticking one stops it before it's built. They never reached the checklist before. A new switch under Feed screen > Popups, Hide the LIVE bubble, off by default, stops the bubble TikTok floats at the top of the feed to point you to a LIVE.

* **TikTok:** Use system font has a second switch, Use system emoji, that draws every emoji with your device's own emoji font. TikTok's fonts don't carry emoji, so most already come from your device, but TikTok fills in the newest ones your device doesn't have with Google's emoji. With the switch on it doesn't, and those may show as a box or in pieces instead. It's off by default and needs a restart. Switch: Hushfeed settings > App.

* **TikTok:** Two new patches, Turn off haptics and Turn off screen transitions. The first stops the short vibrations TikTok plays on its own taps and gestures, the long press buzz included, and your keyboard and your phone's own haptics stay. The second opens and closes TikTok's screens without their slide, while swipes inside a screen still follow your finger. Neither is in the default selection, and each switch starts on once you pick its patch, under Hushfeed settings > App > Appearance.

* **TikTok:** Long press has a new choice, Save the frame on screen as a photo. The press saves the picture the video is showing as a JPEG in the Photo destination folder, DCIM/TikTok unless you've changed it. It's read from the video itself, so the caption and the buttons drawn over it aren't in it. Pause first to pick an exact frame. The file takes the video's name plus where the frame came from, like `_frame_1m05s`.

* **TikTok:** Custom offline videos limit now goes up to 10,000 videos (it stopped at 1,000), and the limit's row says about how much storage that many videos take. How many TikTok actually sends is still up to its servers.

* **TikTok:** Feed filter > Advanced has a new switch, Keep offline videos out of the feed, off by default. TikTok slips the videos it saved for offline viewing back into For You when it can't load enough new ones, and with the switch on they're all taken out, the whole offline fallback list included. Your offline list stays as it is, and Filter TikTok's offline videos greys out while the new switch is on.

* **TikTok:** Long press has a new choice, Set a sleep timer that closes TikTok. The press opens a picker of 15, 30, 45, 60 or 90 minutes, and a toast says when TikTok will close. When the time runs out the video stops and TikTok closes the way a swipe away from Recents closes it, background play included. Pressing again while a timer runs offers to turn it off. If the phone slept through the end with nothing playing, TikTok stays open rather than closing on you the next morning.

* **TikTok:** New patch, Stop recording watch history, with a Privacy switch that stays off until you turn it on. It holds back the view report TikTok sends for each video you watch, which is how videos get into Activity center > Watch history. Your views stop adding to view counts and For You has less to learn from, and TikTok still sees likes, follows, searches and its own usage logs. Videos already in Watch history stay.

* **TikTok:** Hushfeed settings > Feed filter > Seen videos has two new rows, Save seen history to a file and Restore seen history from a file. Save writes the signed-in account's seen videos, and when each was seen, to a JSON file that doesn't name the account or carry any settings, and the row says plainly that the file is viewing history. Restore checks the whole file first, then adds it to the account that chose it. A video in both keeps the newer date, and the banner counts what was added and what was already there, too old, dated ahead of the phone's clock or past the 10,000-video limit. Switching accounts or stopping a slow file app before the merge changes nothing, and a save the file app already has is reported once it answers. TikTok's own data export still goes through Import watch history.

* **TikTok:** The Blocked caption words and Hidden LIVE categories editors in Hushfeed settings > Feed filter have a sample box now. Type a caption or a category under your rules and the editor says which rule matches it, that nothing matches, or what's wrong with a rule, before you save anything. It runs the same matching the feed uses, and it only covers these word rules, not every filter. Nothing is saved, counted or sent while you try it.

* **TikTok:** Screen time has a new switch, Don't start the first video, off by default (#83). When you open TikTok from its icon the first video in the feed waits for one tap, the same catcher Don't start the feed on returning uses, with the tab bar still free. Opening TikTok from a link, a notification (including the ones TikTok sends as an icon tap) or a shortcut isn't held, and neither is a reopen from Recents. The catcher now also leaves room for a tab bar drawn after it went up.

* **TikTok:** The Video, Photo and Comment media filename editors in Hushfeed settings > Downloads now preview the template as you type it. You see the folder and file name a made-up post would get from Hushfeed's downloader and from TikTok's own, plus the sound, details and subtitle files you have on, and anything in braces that isn't a token is named so a typo doesn't go unnoticed until the next save. {original} and {media_id} get their own note under Hushfeed's downloader, which keeps them as typed.

* **TikTok:** New patch, Run beside the store app, for a second TikTok next to the one from the store. Morphe's Clone app patch already gives the patched app a new package name so Android installs both, but the renamed copy registered your phone under that new name, TikTok's servers didn't give it a device ID, and logging in failed. Select this patch with Clone app and the copy registers under TikTok's own name, while each app keeps its own data and login. A build that keeps TikTok's package name isn't touched, and pausing Hushfeed puts the copy's own name back. Google and Facebook sign-in can't work in a renamed copy, so log in there with your email or phone number. It isn't selected by default.

* **TikTok:** Diagnostics has a new row, Log diagnostics for 15 minutes. Tap it before you reproduce a bug and everything is logged for a quarter hour, then logging stops by itself. The row shows when it ends and a second tap stops it early. Restarting TikTok keeps the same end, setting the phone's clock back can't stretch it, and a phone restart ends it. It never moves the Log diagnostics switch, so logging you left on stays on, and backups leave it out. The Log diagnostics switch now says plainly that it stays on until you turn it off.

* **TikTok:** Block the advertising id in Device privacy guard now covers every place TikTok reads it. Two ad SDKs inside the app ask Google's ad service for the id on their own instead of going through Play Services, and they still got the real one. With the switch on they get the blank id too, and every check of whether you've opted out of ads personalization says you have, which is what Android tells apps once you delete your advertising id. Pause Hushfeed still hands TikTok the real answers.

* **TikTok:** New patch, Network proxy. Set an HTTP or SOCKS5 proxy in Hushfeed settings > Region, restart TikTok, and its own network stack sends the feed, search, comments and the rest of its API through it. Plain Java connections go through it too. Videos and LIVE streams stay direct, because TikTok's player opens its own connections. TikTok's network stack can't sign in to a proxy, so a user name and password only reach the Java side, and the password never goes into a settings backup. If the proxy doesn't answer when TikTok starts, a message says so. Other apps aren't affected, and it's off by default.

* **TikTok:** New patch, Picture-in-picture. Turn on Keep watching in a small window in Hushfeed settings > Playback, and when you leave TikTok while a video plays it keeps going over your other apps with just the video showing, no buttons or caption. The window has a play and pause button and takes the video's own shape. It works in the feed and on videos you open from a profile, search or a sound, and needs Android 8 or later. It's off by default.

* **TikTok:** New patch, Always upload in HD. Turn it on in Hushfeed settings > App, under Posting, and every video you post goes through TikTok's own HD upload, just as if you'd switched HD on yourself on the post page. A clip TikTok doesn't count as high quality posts the way it always did. While it's on, turning HD off on the post page won't stick. It's off by default.

* **TikTok:** New patch, Keep pulled sounds. When a sound gets pulled for copyright or in your region, TikTok mutes every video that uses it. Turn on Play sounds TikTok pulled in Hushfeed settings > Playback and those videos play with their audio again, in the feed and in search. TikTok may still show the sound as unavailable. It's off by default.

* **TikTok:** Region spoof has a new switch, Match region fields in requests, in Hushfeed settings > Region. Every request TikTok sends carries the region its servers last saved on your phone and your network's country code, and neither one followed the preset before. With the switch on, both do. The other region fields in a request already follow Match locale and timezone to country. It's off by default.

* **TikTok:** LIVE controls has a second switch, Show exact LIVE viewer counts, under Hushfeed settings > Playback. A LIVE room's viewer count at the top right shows the real number, like 1,234, instead of TikTok's rounded 1.2K, and so does the count in a programmed LIVE, collapsed, expanded or in landscape. It's off by default.

* **TikTok:** New patch, LIVE controls. A LIVE that shows up in your feed can count down and drop you into the room without a tap. Turn on Stop LIVE previews opening by themselves in Hushfeed settings > Playback and it stays a preview until you tap it. It's off by default.

* **TikTok:** Device privacy guard picks up two more switches under Privacy, both off until you turn them on. Hide a VPN connection keeps TikTok from telling you're on a VPN, and only the VPN reads as off to it, so your other connections stay as they are. Block the advertising id hands TikTok the blank id Android gives after you reset yours, so this device can't be matched across apps by it. Both take effect without a restart, and Pause Hushfeed gives TikTok your real VPN state and id back.

* **TikTok:** New patch, Stop search autoplay. Turn on Stop search results playing on their own in Hushfeed settings > App and the videos in your search results stay on their cover until you open one. The feed plays as usual.

* **TikTok:** Ghost mode has a new Hide online status switch under it in Hushfeed settings > Privacy. With both on, TikTok stops sending the activity report that shows your friends a green dot or Active now while you're in the app. That same report brings back your friends' status, so theirs may stop updating for you while it's on. It's off by default.

* **TikTok:** Look like the store app can now name a different store as TikTok's installer. Pick Play Store (still the default), Galaxy Store, AppGallery or Amazon Appstore under the switch in Hushfeed settings > App, then restart TikTok.

* **TikTok:** Swipe for brightness and volume, a new switch under Feed screen > Gestures, lets you drag up or down along the left edge of a video to change the screen brightness, or along the right edge to change the volume, with a small level shown on screen. It works on the feed and on a video opened from a profile. A drag that starts anywhere else still scrolls the feed, and a tap does what it always did. Edge strip width sets how wide the edges are, from 5 to 30 percent of the screen. It's off until you turn it on, and the brightness goes back to your phone's setting when you leave the video.

* **TikTok:** Hide inbox items now reaches inside a chat too. Under Inbox > In a chat there are three new switches, all off until you turn them on: Hide call buttons takes the voice and video call buttons off the top of a chat, Hide sticker suggestions drops the sticker banner, and Hide suggested replies stops TikTok's reply suggestions and the banner that introduces them. The chat details button and everything you send are left as they were.

* **TikTok:** Fade the video controls, a new number under Clear display, dims the buttons, caption, music disc, search bar and tabs over the video to the level you pick instead of hiding them, which helps against screen burn-in while you can still see where everything is. 100 leaves TikTok as it is, and faded controls still take taps. At 0 the buttons and caption are hidden the way Clear display hides them, and the tabs stay at 10 so you can still find your way around.

* **TikTok:** Lock the feed, a new switch under Screen time, keeps For You, Following and the other feed tabs behind a calm panel, and the feed won't swipe. Inbox, profiles and search work as usual, a link to one video still opens that video, and the app opens on Inbox instead of a feed you've shut. The Friends tab's feed is covered too, and clearing the controls doesn't lift the panel. Only a link to a single video gets through, not a profile or a search link. With Wait a day to loosen the budget on, turning Lock the feed off waits until the day starts over, the same as a longer budget does. It's off until you turn it on, and Pause Hushfeed turns it off with everything else.

* **TikTok:** New patch, Block popups. Its checklist under Feed screen > Popups lists each popup TikTok has tried to show on your phone, like the follow-your-friends card, and stops the ones you tick the next time they come up. Nothing is blocked until you tick something. CAPTCHA, verification, sign-in, age, ban and legal consent screens never show up in the list and are never blocked.

* **TikTok:** New patch, Story controls. Two switches under Playback, both off until you turn them on. Loop a story replays a story from the start when it ends instead of moving to the next one, and Hold a photo story keeps a photo on screen until you tap or swipe. Tap or swipe still moves on either way.

* **TikTok:** Export comments, a new switch under Comments, adds Export CSV and Export JSON under the comment search box. Each one saves the comments and replies the video has loaded to a file you pick, with the author, text, time, likes, reply count, pinned, creator liked, picture count and language for every row, and a count beside the buttons that matches the rows in the file. Open the reply threads you want first, since TikTok only loads a thread's replies when you do. If your file app stalls, the app offers Stop waiting. It's off until you turn it on, and it needs Search within comments.

* **TikTok:** Remove feed ads now also keeps the "Find fresh ideas on Lemon8" card out of For You. It's an install promo TikTok slots between videos, so no ad flag marks it. Hushfeed stops TikTok asking for it and drops it from the feed if one arrives anyway, and it shows up in the feed filter report under Ads.

* **TikTok:** New patch, App lock. Turn on Lock TikTok under Privacy and TikTok asks for your fingerprint, face or screen lock PIN when it starts, and again when you come back after the delay you pick (right away, or after 1, 5 or 15 minutes). Nothing shows and the video stays stopped until you verify with your screen lock. A link you open from another app still lands on its video once you're in, and TikTok's preview in recent apps is blank while the lock is on. Android 9 and up use the system authentication prompt, and older phones get the screen lock check. A phone with no screen lock opens TikTok as before and says why. Pause Hushfeed leaves the lock on, so pausing isn't a way past it.

* **TikTok:** Advanced downloads can save a photo post as one MP4 with its sound. Turn on Save photo posts as a video under Downloads. TikTok's Download video on a one-photo post then makes a clean video with no logo, handle or end card, and a post with several photos asks whether you want the photos you picked or a video of them. Each photo shows for 3 seconds by default (Seconds per photo goes from 1 to 10), the sound is cut or repeated to fit, and if it can't be fetched the video saves silent and the notice tells you.

* **TikTok:** New patch, Prefer H.264 playback. Turn on Prefer H.264 video under Playback and a video TikTok also offers in H.264 plays that version instead of HEVC or ByteVC2, which can help a phone that stutters or runs hot. A video without one plays as before. With Play SDR instead of HDR or Playback quality on too, they pick among the H.264 versions.

* **TikTok:** New patch, Show engagement rate. Its switch under Feed screen > Video info works out a video's likes, comments, shares and saves as a percentage of its views, from the counts TikTok already sent with it, so nothing extra is downloaded. The rate shows after the creator's name on the video you're watching and after the view count on profile grids, like 12.3K · 4.2%. A video without a view count shows nothing.

* **TikTok:** New Show exact counts switch in Feed screen > Right column. It shows likes, comments, shares and other counts as the full number, like 1,234,567 instead of 1.2M, using your phone's digit grouping.

* **TikTok:** New patch, Custom launcher icon. It gives TikTok's icon a themed layer, so with themed icons on in Android 13 and up the note takes your wallpaper's color. Its Icon style option also swaps in a black background, or a plain white-on-black or black-on-white note, on Android 8 and up.

* **TikTok:** New patch, Lift text length limits. Its switch under Comments > Writing lets a comment, a repost note or your bio run past the length TikTok's app stops typing at, instead of cutting you off at the counter. TikTok's servers still decide how long a text they'll take.

* **TikTok:** New patch, Remove avatar rings. Two switches under Feed screen > Right column take the story ring and the pulsing LIVE ring off profile pictures on the feed and in comments, and the story ring off profiles too. With them on, tapping the picture opens the creator's profile instead of their story or their LIVE.

* **TikTok:** New patch, Play SDR instead of HDR. Turn it on under Playback and videos that also come in HDR play their standard version, so the screen doesn't jump to full brightness when one starts. A video TikTok only offers in HDR still plays. With Playback quality on too, it picks among the standard versions.

* **TikTok:** New patch, Copy bio and IDs. Long-press a profile's bio to copy it. The share sheet a profile's menu opens gets Copy username and Copy user ID buttons, and a video's share sheet gets Copy video ID. Each copy says what it copied in a toast. Switch: Hushfeed settings > App > Copy bio and IDs.

* **TikTok:** New patch, Show follow status. Open a profile that follows you and "Follows you" shows under its @username. If you follow it and it doesn't follow you back, it says that instead. Your follower and following lists mark the accounts you follow that don't follow you back. It uses the follow status TikTok already sends, so nothing extra is fetched. Switch: Hushfeed settings > App > Show follow status.

* **TikTok:** Feed filter has a new Show only mutual friends on the Friends tab switch. With it on, the Friends tab drops posts, reposts and LIVEs from accounts you follow that don't follow you back, and from strangers and suggested accounts. A friend's repost stays even when the video is someone else's, and your own posts and reposts always stay.

* **TikTok:** Hide AI-generated videos catches a lot more now. Besides TikTok's visible AI label, it reads the AI marks TikTok keeps on a post without always showing them: the creator's AI disclosure, TikTok's own moderation verdict, AI sources in the content credentials (C2PA), posts made with TikTok's AI effects like AI Alive, AI remix and AI portrait, and tags like #ai or #aigenerated. The filter report counts each one under AiSignals by the mark that caught it. Feed filter also covers the newer version of the Friends tab feed, so its rules apply there too.

* **TikTok:** Show where a video was posted now works on videos where TikTok hides the post time, which is most of For You unless Always show publish date is on. It also shows on videos you open from search, a creator's profile or a shared link, and it switches to the right country as soon as an opened video starts instead of keeping the one from the feed. A long name gets shortened a little so the country isn't cut off along with it.

* **TikTok:** In Feature Gate Lab, turning overrides on from a gate's own page now makes its Forced result switch available right away. Before, it stayed greyed out until you left the page and came back.

* **TikTok:** Remove LIVE extras now says in its description that it can take away the red and blue battle score bar and the co-host guests' names in LIVE. It's off unless you pick it, so leave it unticked if you watch battles.

* **TikTok:** Drop the animated image cache works differently now (#100). The old version threw away cached frames, so animated stickers and GIFs stuttered and memory use didn't go down. Now TikTok keeps only the frame on screen for each animation and stops decoding frames ahead of time, and each next frame is drawn from the one before it.

* **TikTok:** Hide the Clear display controls moved to Feed screen > Clear display, right under Automatic clear display, instead of the end of Around the video.

* **TikTok:** Clear display now keeps the bottom tabs (Home, Friends, Inbox, Profile) away on the main feed until it ends. With Automatic clear display on, they could stay over the first video after TikTok was opened fresh. Other pages, like Inbox opened from a notification, keep their tabs.

* **TikTok:** With Automatic clear display on and no delay, the next video stays cleared while you swipe to it, instead of showing its buttons, caption, progress bar and tabs for a moment (#84). Tapping Restore display still brings everything back.

* **TikTok:** In Clear display, the search, place or product link under the caption can't be tapped by accident anymore, and neither can the wide search bar newer TikTok versions put at the bottom. TikTok only faded it out, so a tap near the bottom of a video could open a search you couldn't see (#84).

* **TikTok:** Clear display now also takes the Add comment bar off photos and videos you open from search or a profile, and Restore display brings it back (#84). Before, it stayed at the bottom unless Hide the comment bar on opened videos was on.

* **TikTok:** With Hide the Clear display controls on, the progress bar is still there to drag, just invisible, so you can move through a video along the bottom edge without leaving Clear display (#84).

* **TikTok:** Feature Gate Lab has 13 new reviewed presets for features other TikTok mods enable by flag. There's repost with a comment, the profile banner with the new profile layout, live photo, camera and audio comments, comments saved to Favorites, comment sort and dislike styles, message bubble colors with the Inbox archive and sharing to more chats at once, Manage topics, visual search, AI Self, the long-press menu on every post, TikTok's own hold to speed up, its background play and auto-scroll, and post dates in the feed. Every preset was checked against 47.0.3, 47.1.3 and 47.1.4. Show See translation was 47.1.3 only and now covers all three. The list shows just the presets for the TikTok you have installed. Some of these features also depend on what TikTok's servers allow for your account.

* **TikTok:** Remove ads now also catches creator posts TikTok runs as ads. They don't carry TikTok's ad flag, so they were getting through, but TikTok marks them in the post's commerce details and Hushfeed reads the same mark TikTok does. The filter report counts them under AdSignals. Skip the splash ad also stops the startup tasks that preload TopView takeover ads and ask the server for a real-time splash.

* **TikTok:** Feed filter has a new Hide unpersonalized For You videos switch. It hides the fill-in videos TikTok pads For You with when it hasn't picked anything for you, the ones it sends from its for_you_page_999 pool with no reason attached. If a whole batch is fill-in, the batch stays, so the feed never stalls on it. The filter report counts each For You batch by the pool TikTok says it came from, next to how many the switch took out. Creator exceptions get through it the same way they get through the other preference filters.

* **TikTok:** Searching Hushfeed's settings puts a setting whose name matches above one that only mentions those words in its description, so "original photos" now lists Download original photos first and Photo filename after it. Searching "debug", "debugging", "logs" or "logging" finds Log diagnostics, and each translation has its own words for it too.

* **TikTok:** A photo TikTok only sends as HEIF now has a second way to become a JPEG. Samsung's image decoder turns TikTok's HEIF down, so Hushfeed reads it the way the phone's media scanner does instead, and keeps the file TikTok sent only when neither one can read it.

* **TikTok:** What's new can show a release in the phone's language now (#91). The 0.68.0 and 0.67.1 notes are translated into all eight languages Hushfeed's settings come in, and older releases stay in English with a short line saying so.

* **TikTok:** With debug logging on, the diagnostic file now lists each share sheet item Hushfeed hides, which row it sat in and what kind of view it was (#120). People in the Send to row are listed without their names. It's there to pin down why TikTok's smaller share sheet loses its rows when something is hidden.

* **TikTok:** The FAQ on Hushfeed's GitHub page explains the pure black Profile and Inbox some people see without AMOLED dark theme. It comes from TikTok 47.1.4's own dark mode, and a build without that patch shows the same colors with Hushfeed paused (#69).

* **TikTok:** The troubleshooting guide on Hushfeed's GitHub page now answers whether Feature Gate Lab can hide CAPTCHA popups (it can't), and walks through trying one of the Lab's reviewed presets (#111).

## 0.68.0 (2026-10-06)

* **TikTok:** Download original photos saves every photo as a JPEG (#105). TikTok lists each photo as a HEIF copy first, so they were landing as .heif files that a lot of gallery apps and computers can't open, and some phones, Samsungs included, can't decode TikTok's HEIF at all. Hushfeed now takes the WebP copy TikTok lists beside each HEIF and saves it again as a full-size JPEG on Android 9 and newer. When TikTok lists a JPEG copy, that one is saved byte for byte instead. A photo that can't be converted keeps the file TikTok sent, and so does one with see-through parts. The setting's description says JPEG now too, and story photos work the same way.

* **TikTok:** Hiding the status bar now also removes the black strip TikTok keeps above videos for it, and on Android 14 and older the video draws behind the camera cutout instead of below it (#97). A new Hide the Clear display controls switch takes the close button, progress bar and pause and speed buttons off the screen while Clear display is on, along with the close button photo posts show in the corner. They're back the moment you leave Clear display.

* **TikTok:** Clear Display now hides the stories button above Following when TikTok leaves it up, and puts it back as soon as you leave Clear Display (#84). Pause leaves both the stories button and the top tabs to TikTok. Automatic Clear Display also waits until TikTok has really cleared the screen before it counts a try, so a first video TikTok wasn't ready for still gets cleared.

* **TikTok:** Automatic Clear Display retries on the current video after focus returns, including the first video after startup (#84). A canceled or failed attempt no longer prevents another try. Manual exit remains respected, and callbacks from an earlier video cannot cancel the next video's timer.

* **TikTok:** Back up, Restore and Import watch history don't get stuck behind a slow file app anymore. If the file app hasn't answered after a few seconds, the row offers to stop waiting, and stopping before anything was read or written leaves your settings and history as they were. A backup the file app already has is the one exception: the row says the file app hasn't said yet whether it saved it, and you get the real answer once it does. A file app that never lets go of a file holds only that row, which says so, instead of piling more work up behind it. Turning the phone mid-restore keeps the restore going.

* **TikTok:** Feature Gate Lab's Export loaded values and Import loaded values don't get stuck behind a slow file app anymore. If the file app hasn't answered after a few seconds, the Lab offers to stop waiting, and stopping before anything was read or written changes nothing, not even a file you picked to replace. Tapping either one again while a file is still out says so, where it used to queue another to run whenever the first one ended. Exporting over an older, bigger export no longer leaves the end of the old file behind, which made the new one unreadable.

* **TikTok:** Picking an existing backup file to replace no longer risks losing it. If the backup stops, or fails before anything goes into the file (a feed rule list too long for a backup, say), the file you picked keeps your older backup instead of being deleted. An empty file the picker made for the backup is still cleaned up.

* **TikTok:** Settings search lists the rows where your search is a whole word first. Searching "counts" used to put Hide verified accounts and Blocked creators, where it only turns up inside "accounts", above the rows that are about counts. Rows that match only inside a longer word still follow, in page order, and translated settings sort by the same rule.

* **TikTok:** Settings search now finds every box in a checklist, ticked or not, so searching "counts" finds Counts under the buttons in the right column list. Opening a box's result opens the list scrolled to that box without changing it. A list that's open when the screen rotates comes back with your unsaved ticks.

* **TikTok:** Switch rows in Hushfeed's settings change shape when the screen is narrow and the text is large. The switch moves under the text, at the end of the row, and the title and description get the whole width. A German title at twice the text size on a small phone no longer breaks mid-word into four short lines. Wider screens keep the usual layout, and a row scrolled from one shape into the other changes back.

* **TikTok:** What's new now tells you the notes are in English when the rest of the settings are in your language. The notes are also marked as English text for screen readers.

* **TikTok:** Undo clearing seen videos now restores history in the background and reports how many cleared records still fit the history limits. It no longer says videos were restored when newer watches leave no room for them. A failed save keeps Undo available.

* **TikTok:** Undo now commits watches recorded during restoration before counting them. Older queued writes also preserve newer watch times that Undo has already saved.

* **TikTok:** Media saves now honor the server's full retry delay, including HTTP dates, before following a redirect or trying another mirror or subtitle track. A delay beyond the save's time limit stops the job with a clear message and keeps files already saved. Cancel also interrupts a retry wait.

* **TikTok:** Saving a diagnostic report or a feature gate report runs on a thread of its own, so a slow media store can't hold up the threads the rest of Hushfeed shares. Tapping Save again while a report is still saving says so instead of starting a second one.

* **TikTok:** Hushfeed now builds on Morphe patcher 1.15.1, so it needs Morphe Manager 1.34.0 or newer. Manager 1.33.0 asks for an update before it loads the bundle. ARSCLib follows the patcher to 9b742c412d, with its hashes checked against the downloaded files, and the release checks patch with desktop CLI 1.18.1.

* **TikTok:** Every Guava request in the build now resolves to 33.7.2, the release outside GHSA-xxph-c9ww-hj94, across the settings, plugin and project graphs rather than only where the catalog's pin reached. Its checksums were verified against Maven Central before the strict dependency check accepted them. Guava stays on the build and patching side: the patch bundle and the extension carry none of it. The check that patch sources don't import Guava directly used to pass without reading a file. It reads them now.

* **TikTok:** An edit to the Java extension alone no longer reruns the fingerprint checks against the APK fixtures, since those never read it. Full test commands and release checks still cover every test, and a change to a patch, a test, a dependency or a fixture still reruns them.

* **TikTok:** Documentation edits no longer rerun unchanged APK fixture tests. Full test commands and release checks still cover every test, and source-reading checks now track their external inputs. Replacing an APK invalidates test results even when its size and timestamp match.

* **TikTok:** Local storage scanner checks now keep Java diagnostics visible without treating successful runs as failures in Windows PowerShell.

* **TikTok:** Local APK signing now accepts harmless Java startup diagnostics from heap and CPU limits. Invalid certificates and failed signing checks still stop installation.

## 0.67.1 (2026-10-03)

* **TikTok:** Settings are easier to find. All five buttons Hushfeed draws on videos sit together under Feed screen > Buttons on videos. Playback now holds every player switch, including the progress bar and its thumbnail, stop looping and resume after scrolling, which were on App. Screenshots and the status bar moved to App > System along with Look like the store app. Allow Duet and Stitch is on Share sheet now. Your saved choices stay as they were.

* **TikTok:** Every button Hushfeed draws on videos now starts off: block, hide, block sound, Not interested and mute. A fresh install shows none of them until you turn one on under Feed screen > Buttons on videos. If you used the mute button, turn on Show the mute button on videos again.

* **TikTok:** Remove unused language packs no longer stops the whole patch run when Languages to keep is left at all and the APK carries a language set that hasn't been checked, like a split bundle merged with a single language. Nothing is removed then, and the patch log says why (#96).

* **TikTok:** The streak line under Keep a streak going says "Sent to 15/15 chats" once TikTok has taken every message. It calls delivery unconfirmed only for a chat whose hand-off hit an error or never finished (#92).

* **TikTok:** Show the hide button and Show the block sound button now draw their own feed controls even when Show the block button is off. Both work through Feed filter, so they're listed only when that patch is in. New installs start those two helper switches off, so choosing Block author button doesn't add extra chips until you ask for them (#95).

* **TikTok:** Pause keeps the saved Home long-press shortcut available, so guests can reopen settings and resume Hushfeed after closing the screen. A disabled shortcut stays disabled, ordinary Home taps still work, and TikTok's own long-press action is preserved (#89).

* **TikTok:** Settings on Android 6 use the shipped languages' plural forms, including Russian one, few and many. Missing quantity translations fall back to English with the right singular form. Newer Android versions keep their own ICU rules.

* **TikTok:** What's new keeps its full release history as the text grows. Large notes and international text no longer exceed Java's single-string build limit.

* **TikTok:** Morphe Manager 1.33.0 shows all 106 patches in their ten categories, with nothing left uncategorized. The checks run before each build now cover single-ABI inputs on every supported TikTok version and reject altered native libraries or an empty patch result. Published bundles can be checked against GitHub release attestations or a pinned signing key.

* **TikTok:** Limit background traffic keeps push setup intact by default, including when All is selected. Its optional Skip push setup choice starts off and can stop message and other notifications when enabled. Existing APKs need repatching from TikTok's original APK to restore push setup. Pause can't reverse this patch-time change (#86).

* **TikTok:** Restoring settings now reports keys the current build can't restore, separately from settings missing from an older backup. Portable settings still migrate across TikTok versions, local device state stays intact, and Feature Gate Lab compatibility and Undo keep their existing behavior.

* **TikTok:** Feed filter's Seen videos section can import the reviewed TikTok JSON watch-history export. Watch dates and the chosen account stay attached to the import, the existing age limit applies, and a banner reports added and skipped entries. Repeating an import adds nothing. Invalid or unsupported files and failed writes leave saved history unchanged. Files are limited to 2 MB and 10,000 entries, and links never leave the phone. An import that adds history ends Undo for an earlier clear and explains that change.

## 0.67.0 (2026-10-02)

* **TikTok:** New patch, Block suggested video notifications, stops TikTok's "Videos you might like" pushes, the ones about popular videos it picked for you, like "25M+ people viewed". Its switch under Inbox starts on once the patch is in, so they're blocked from the first launch, and turning it off lets them through again. Messages, comments, likes, follows and videos from accounts you follow aren't touched.

* **TikTok:** Hide search rewards (App) now keeps the floating coin off search as well as the points banner (#21). The switch only reached the banner's service before, so the coin stayed and the banner could come back. Now every search rewards piece takes the path TikTok already takes for accounts without rewards. TikTok decides this once per launch, so the switch now asks for a restart after you change it.

* **TikTok:** Hushfeed's settings come in Azerbaijani (#77). Like the other languages they follow the language TikTok runs in, and it's a first pass, so corrections are welcome.

* **TikTok:** The four resource optimizers accept a universal APK split down to one ABI, such as an arm64-v8a only copy (#43). They used to stop because the other ABI's libraries were missing. Every file the split kept still has to match its reviewed checksum, and a set missing only part of an ABI is still refused.

* **TikTok:** Show author region puts the country on the video you're watching again after you swipe down (#75). TikTok keeps the videos above and below attached while you scroll, and the country was going on the one above, so it only showed after swiping back up.

* **TikTok:** New patch, Stop saving search history, keeps new searches out of the search history TikTok saves on the phone. Its switch, Privacy > Don't save new searches, starts on once the patch is picked. Typed and suggested searches still work, searches you'd already saved stay until you delete them, and turning the switch off or pausing Hushfeed lets TikTok record again. It doesn't touch anything TikTok keeps on its servers.

* **TikTok:** Streaks take several usernames separated by commas or new lines (#73). Each chat has its own attempts, so a recipient that couldn't be found can retry without repeating another chat's message. Duplicate names and names for the same chat are counted once. The sending account is checked again before each message. TikTok's reply receiver doesn't confirm delivery, so the status says delivery is unconfirmed and avoids retrying a chat that may already have received its message, even after the process stops.

* **TikTok:** New patch, Feed text sizes, gives the video description and the creator's name their own sizes on Feed screen, from 8 to 48 (#66). Both start at 0, which keeps TikTok's size, and Android's font scaling still applies. Spoken captions keep their separate size.

* **TikTok:** Build details under About copies or saves build identity and verified patch-time resource choices, even without Diagnostic tools or events. Automatic reports include the same facts. Older APKs show unknown, and runtime settings can't rewrite them. Patching an APK that Hushfeed already patched now stops with an error, so start from TikTok's original APK.

* **TikTok:** A bundle saved under a filename or folder with a plus sign, a space, a percent sign or non-English letters now applies. The patcher used to read `+` as a space and stop with "no such file".

* **TikTok:** Recovery records for unfinished saves survive Android cache clearing. Existing records move only after a verified commit, and unreadable records stay intact.

* **TikTok:** Finishing a download releases its temporary-file ownership even if deletion fails, so later cleanup can reclaim the abandoned file. Mirror retries keep their active ownership.

* **TikTok:** Share sheet actions you exclude by name are matched on the titles TikTok exposes to accessibility. The panel's attachment, layout and draw events recheck late labels and recycled rows, including a reopened dialog (#74).

* **TikTok:** CAPTCHA challenges that can't be classified or read stay visible, including on signed-in accounts. Hide CAPTCHA popups is unavailable until a browsing scene is validated, and shown decisions still appear in diagnostics.

* **TikTok:** Hide the music line now hides the track name and its "Contains:" song credit on feed and opened videos (#68). The music disc still has its own switch.

* **TikTok:** The README's install steps and TikTok badge list all three supported builds, and it explains which patch-time removals need a new APK to undo. The settings tour uses the current Share page, without the removed friend-send confirmation.
* **TikTok:** Video quality and Remove sound no longer intercept photo posts that also carry a video model (#71). Download original photos keeps control of still-image saves, and selected live-photo motion clips keep TikTok's native route.
* **TikTok:** Download video on a photo post now queues original stills before TikTok starts video conversion. Missing originals produce an error, and even one or two images show a file count and Cancel. Live Photo video choices retain their native route (#71).

* **TikTok:** The LIVE feed you open with the LIVE button can be filtered now (#57). Feed filter has a LIVE feed section that hides gaming, Shop and sponsored LIVEs, verified creators, categories you name, and rooms outside a viewer or follower range. Blocked creators, Creators hidden on this phone and Blocked caption words carry over (words are matched against each LIVE's title), and Creator exceptions lets chosen creators past the gaming, verified, category and count rules, though never past a block, a blocked word, Shop or sponsored. The first room comes with the LIVE button itself, so the rules start with the one after it. When they hide every room on five pages in a row, one room gets through, because a page with nothing on it makes TikTok ask for the next one about once a second. TikTok's LIVE feed doesn't say when a stream started or when an account was made, so there's no rule for either. The diagnostic report's LIVE FEED section counts pages, rooms and what each rule hid.
* **TikTok:** A double tap, long press or left swipe set to open comments opens the comments of the video on screen (#63). Right after a swipe it could open the comments of the video before or after it, show that video's "restricted comments" message, or say comments weren't available. It went by the video the player was naming, and the player names a new video only once it starts playing. When what's on screen has no comment button of its own, you get the "not available" message rather than another video's comments.
* **TikTok:** The other long press choices go by the video on screen too (#63). Copy the video link, Copy the sound link, Save the original sound and Find the sound on YouTube Music took the video before right after a swipe, so a link copied then was that video's. When what's on screen has no comment button of its own, like a LIVE, they say there's nothing there for them instead. Seek from the edges no longer moves the video before either, and if the new one hasn't started playing yet it says nothing is playing. In a video opened from a profile, a comments gesture never reaches the feed behind it.
* **TikTok:** Hide the status bar now also keeps it away on videos you open from a profile, a hashtag, a sound or search (#50). It used to stop at the main feed, so the clock and status icons stayed over those videos.
* **TikTok:** A new switch on the Feed screen page, Hide the comment bar on opened videos, takes the Add comment bar off videos you open from a profile, a hashtag, a sound or search, and the video grows into the room it held (#50). TikTok's Clear display hides that bar but leaves a black strip where it was. The comment button on the right still opens comments. It's off by default.
* **TikTok:** A new switch on the Privacy page, Stop TikTok's benchmark runs, keeps TikTok's phone benchmark from starting (#64). TikTok's servers sometimes ask it to time the phone, and it does that in a background process of its own that held about 194 MB on one phone. The switch turns that process's service off, so it can't start. A run that's already going ends when TikTok restarts, and turning the switch off or pausing Hushfeed hands the service back. Turn it off before you patch TikTok again without that patch, or the benchmark stays off until TikTok is reinstalled. It comes with the Resource and battery governor patch and is off by default.
* **TikTok:** New patch, Hide profile shortcuts, takes the shortcuts you pick off the row under a profile's bio, like TikTok Studio or Your orders (#49). TikTok's server decides what goes in that row, so Hushfeed settings > App > Hide profile shortcuts lists the ones TikTok has sent to your phone once you've opened your profile, and the row beside it takes names you type. A change shows once TikTok restarts. Hide all of them and the row goes away with no gap left behind. Nothing is hidden until you pick something.
* **TikTok:** Hushfeed works in a second copy of TikTok made with Morphe's Clone app patch (#59). That patch gives the copy a new package name and renames TikTok's own resources to match, so the share sheet tools couldn't find their views, and neither could hiding video overlays, the captions settings, the comment tools or the author line. They now look views up under whatever name the app runs as. Logging in to a cloned TikTok was refused on the first try in testing, so the copy may only be usable logged out.
* **TikTok:** Remove unused language packs keeps every language now unless you list the ones you want in Languages to keep (#67). It used to keep only English out of the box, so picking every patch in Manager left TikTok in English for everyone else. To keep removing them, set Languages to keep to your codes, such as en or en, tr. If your TikTok lost its language this way, patch it again with this patch off (or with your language listed) and install it over the one you have. You stay signed in.
* **TikTok:** In Feed tabs, the Explore option is called Explore or Community now (#62). Some accounts see that tab as Community, and unticking Explore hid it with nothing in the list saying so.

* **TikTok:** Hushfeed now needs Morphe Manager 1.33.0 or newer, because it's built against Morphe patcher 1.15.0. Older Managers say the bundle needs a Manager update. In a test with memory held near what Manager gets on a phone (768 MB), patcher 1.14.1 ran out of memory while saving patched TikTok 47.1.4, and 1.15.0 finished.

## 0.66.0 (2026-10-01)

* **TikTok:** Hushfeed's settings now come in Italian (#61) and Russian (#60). Both are first passes, and corrections from Italian and Russian speakers are very welcome. Russian counts use all three of the language's forms, so 2, 5 and 21 results each read the way Russian says them.
* **TikTok:** New patch, Change app name, puts a name you choose under the app's icon and in Android's app list, so a patched TikTok is easy to tell from another one (#59). Type the name in the patch's options in Morphe Manager. It's off by default, inside the app everything still says TikTok, and like Hide Play Store update offer it has the manager decode TikTok's resources, which takes more memory.
* **TikTok:** Fit the video to the screen fits a video again when the space around it changes size (#29). A Pixel 9 showed a video fitted to about two thirds of the screen's height with bars on every side: the fit had been worked out against a space TikTok then laid out bigger, and nothing fitted the video again. The diagnostic report also has a VIDEO FIT section with the last few fits and the sizes each was worked out from, so a report taken when it happens shows where a wrong size came from.
* **TikTok:** A phone set to English first and another language second shows Hushfeed's settings in English, as TikTok does, instead of in the second language. Counts also follow the rules of the language the settings are shown in. A phone whose language has no table yet showed "1 results", and one set to Japanese and then Russian showed "1 результатов".
* **TikTok:** In Brazilian Portuguese a count of zero read as one, "1 resultado" for no results at all, because Portuguese counts 0 together with 1 and that form is written for exactly one. A count other than 1 now always shows its own number. A language whose one form covers more counts, like Russian's 21 and 31, gets a row of its own for them.
* **TikTok:** The diagnostic report has a FEED MUTE section, so a report of a feed with no sound can tell Hushfeed's mute apart from everything else (#58). It shows whether Mute feed videos and its button on the feed are on, how many feed players it has muted since TikTok started, and how many audio focus requests it has turned down. When Hushfeed is paused it says so, since the feed then plays with its sound.
* **TikTok:** The share sheet no longer shows "Tap again to send" on a friend you never touched (#58). That message came from the Confirm before sending to a friend switch, which was hooked to the code TikTok runs when a friend's picture scrolls into view, not to a tap, so it could ring a friend as the sheet opened and it never held back a real tap. On every TikTok version Hushfeed supports, tapping a friend only picks them and nothing goes out until you press Send, so the switch is gone rather than moved.
* **TikTok:** You can add apps of your own to the share sheet's Share via row. Pick them under Hushfeed settings > Share sheet > Add apps to Share via, from every installed app that takes a shared link. Each one shows up just before More with its own icon and gets the same link the other apps get, so Sanitize sharing links cleans it too. An app TikTok already shows is left alone, and one you've since uninstalled drops out of the row until it's back.
* **TikTok:** New patch, Look like the store app, for a follow or a like that undoes itself on a refresh on a patched build (#55). A patched TikTok is re-signed with the patcher's key and installed by Morphe Manager, and TikTok reports both about itself: its signature hash and its installer. This answers those reports the way the Play Store app would, so TikTok's signature hash carries TikTok's own certificate and its installer reads as the Play Store. TikTok can also read the APK on disk from native code, which no patch reaches, so this may not be enough on its own. It's off by default, under Hushfeed settings > Privacy, and needs a restart to take effect.
* **TikTok:** New patch, Keep a streak going, sends one message a day to someone you pick, at a time you pick, so your message streak with them doesn't lapse on a day you don't open TikTok. Set it up under Hushfeed settings > Inbox > Streak: their username, the time and the message, which is a 🔥 unless you change it. It goes out through TikTok's own notification reply, and TikTok doesn't have to be open. When it can't send, it tries again every 15 minutes, up to three more times, and a message that still didn't go out that day is sent if you open TikTok later the same day. Send it now sends today's right away, and the line under the switch says when the last one went and when the next one goes. Turning it on, or picking someone new, after today's time has passed starts it tomorrow, and nobody gets two messages in one day, even if you switch away from them and back. It only sends from the account you set it up on. The diagnostic report gets a section on how the last try went, without naming who it's for. The patch is off by default, and it adds the exact alarm and start at boot permissions the daily alarm needs.
* **TikTok:** New switch on the Screen time page, Let the last video finish. When the budget runs out, the video on screen plays to its end before the hold covers the feed, and the feed won't swipe to another video until then. A budget of five videos used to put the hold over the fifth, so you got four. Only the feed's swipe stops. It waits three minutes at most and it's off by default.
* **TikTok:** Hushfeed supports TikTok 47.1.4 as well as 47.0.3 and 47.1.3 (#56), and 47.0.3 stays supported. Every patch applies to each of the three. The trimming patches know 47.1.4's rebuilt libraries and language packs, and Block P2P video relay has nothing to remove from 47.1.4's split bundle, which leaves those libraries out, so it says so instead of refusing the bundle.
* **TikTok:** A new switch on the Feed screen page, Hide effect and template tags, takes the "Try effect", template, CapCut and AI style tags off the strip above a video's description (#51). Place, film and drama tags stay, and Diagnostics counts the tags it took away. The "Contains:" song credit belongs to the music line, which Hide the music line already hides.
* **TikTok:** Patching takes less time (#54). The privacy patches, Ghost mode and Allow screenshots each read every call in TikTok to find the few they change, and a dozen patches searched the whole app for code they could have looked up by class. Now they look only where it can be. On a desktop the patch step for all 98 patches dropped from about 240 seconds to 140, and at a phone-like 768 MB memory limit from about 280 to 180. The patched app comes out byte for byte the same. On a Galaxy S22 in Morphe Manager at 640 MB, the recommended patches plus AMOLED dark theme went from about 8 3/4 minutes to 6 1/2.
* **TikTok:** New patch, Keep playing in the background, keeps TikTok's own background play on whatever its server says (#52). The video you're watching keeps going after you leave TikTok or turn the screen off, and TikTok's media notification pauses and resumes it. It also covers photo posts and the videos on your own profile, private ones included, which TikTok leaves out. A feed video stops at its end, since TikTok doesn't loop or move on in the feed while it's in the background, and another app's sound still pauses it. TikTok's own background play switch in the long-press menu stays on while this is on. It's off by default. The switch is under Hushfeed settings > Playback, and TikTok needs a restart after you change it. Feature Gate Lab shows the switch deciding TikTok's background play value. Mute feed videos now stays muted when the notification resumes a video in the background.
* **TikTok:** Stop on-device AI profiling does something again. Both of the places it looked for had gone from all three supported TikTok builds, so it applied without changing anything, and it searched all of TikTok for them on every patch. It now keeps TikTok's Pitaya plugin from starting. Pitaya's native engine doesn't load, and TikTok no longer hands it a copy of every analytics event it logs. TikTok carries on the way it does when the plugin isn't installed, and patching fails if a TikTok update moves any of this.

## 0.65.0 (2026-09-29)

* **TikTok:** The Screen time page shows Start today over's result straight away, and a page left open while the day starts over shows what the new day changed. Both used to keep the old numbers and switches until you left the page and opened it again.
* **TikTok:** Resource and battery governor's description, and the Block motion sensors switch it adds to Hushfeed's settings, no longer claim a battery saving that was never measured. They say which sensors it stops TikTok listening to.
* **TikTok:** The README and the patch descriptions in Morphe Manager now give measured numbers for the optional trimming patches instead of adjectives: the storage each one saves, and what they did to memory use on a Galaxy S22 (alone or all together, nothing beyond the run-to-run spread, apart from about 13 MB less loaded code with all of them on).
* **TikTok:** AMOLED dark theme's description and the README no longer say it needs a 768 MB memory limit to patch. Measured on two phones, it still finishes at 640 MB, just slower, so 768 MB or more is now advice rather than a requirement.
* **TikTok:** The keyboard no longer rewrites an app's package name in Send links to another app, or a host in Share links through another host. A keyboard like SwiftKey used to capitalize it and add a space after every dot, so com.dv.adm came out as "Com. Dv. Adm" and the field refused it. The fields for handles, country and language codes, sound ids, filename templates and speeds get the same plain keyboard.
* **TikTok:** A list or text setting saved right as you left the settings screen could keep its old value until TikTok restarted, even though the row and the saved file both already showed the new one. Closing a dialog is its own step after the tap on OK, and leaving the screen a beat too soon used to skip past it.
* **TikTok:** When TikTok closes in the middle of a save, the next time you open it a banner says which saves didn't finish, or might not have, and how many of their files are missing. Files that already reached your gallery count as saved, and nothing restarts on its own, so save it again if you still want it. If another message covers the banner before its time is up, it comes back the next time you open TikTok. The start-up cleanup of half-written files from a day or more ago also runs now. It was meant to run every time TikTok started but never did.
* **TikTok:** A save's progress row sitting on one of TikTok's pop-up sheets could close TikTok when the save ended or a second row came up. It stays where the sheet puts it now.
* **TikTok:** With `{creator}/` at the start of the file name, the sound saved beside a video or a story now goes into that creator's folder with it. It went to the download folder one level up, so the two split apart. On Android 10 and newer it goes to the matching creator folder under Music.
* **TikTok:** Every save says it's been taken the moment you ask, sounds, profile pictures, stickers and a live photo's clip included. Hushfeed runs three saves at once and lines up the rest, and a save in line used to show nothing until it started. Now a save with a progress row shows it straight away as waiting, with Cancel to take it out of line, and one without a row says how many saves are ahead of it. Past three rows, a single line counts the rest, with a Cancel that takes the last save in line out of it. Asking again for something already in line says whether it's still waiting or already saving, where it used to do nothing.
* **TikTok:** On Android 10 and older, tapping the block button over a video, Unblock on its banner or Save media on a sticker no longer closes TikTok. Each of them marked itself busy in a way those Android versions refuse.
* **TikTok:** Diagnostics can record a screen's layout for a "please hide this" report. Tap Record a screen's layout, go to the screen, and twenty seconds later the next exported report carries how that screen and any sheet over it are built, without any of its text.
* **TikTok:** The block control, the mute button and Hushfeed's other buttons over the video now show on a video opened from a creator's profile, a hashtag or a sound, and come back to the feed with you. They stayed on the feed behind that screen, where nobody could see them. Mute feed videos mutes those videos too, so the button means what it says there.
* **TikTok:** The budget label and the fade before the hold go as soon as you switch to Profile, Inbox or another tab. Only the playing video moved them, so they could stay over those tabs, the fade nearly black. The fade also stops above the tab row now.
* **TikTok:** When the time budget runs out partway through a long video, the notice and the hold come right then. They waited for the next video, so a long or looping one could play well past the budget. Lowering the budget below what you've already watched today brings them on the same video too.
* **TikTok:** New row under Downloads, Forget saved videos. It empties the record behind the already-saved check, keeps the files and your settings, and offers Undo while its notice is up. A save that was already running when you forgot doesn't add itself back.
* **TikTok:** Mute feed videos leaves another app's music playing in two more cases. Turning mute on from the settings page now gives the sound back when you return to the feed, and turning the sound back on while a video is paused makes the feed ask for it when it plays again, rather than playing over the other app. The mute button also stays off stories and LIVE replays, which keep their sound.
* **TikTok:** Blocking a commenter from the comments is remembered for the account that did it. After switching accounts, someone the first account blocked showed as blocked for the second, and the first tap there sent an unblock. A tap on the row while the banner's Undo is still working no longer sends a second unblock.
* **TikTok:** Hushfeed now needs Morphe Manager 1.32.0 or newer, because it's built against Morphe patcher 1.14.1. Older Managers say the bundle needs a Manager update. The new patcher writes Hushfeed's changes into TikTok's own resource table instead of rebuilding it, so everything the patches don't touch stays exactly as TikTok shipped it. The old one dropped the quote marks around about 70 translated phrases in TikTok's effect tools and widened the type of five attributes. Patched code is the same with either patcher on both supported TikTok builds.
* **TikTok:** Press and hold the Home tab to open Hushfeed's settings (#45), rather than going through Profile, the menu and TikTok's own settings. A tap on Home works as before. It comes with Feed tab navigation and has its own switch under Feed tabs.
* **TikTok:** Settings say more exactly what's going on. A Reset or Undo that fails names itself instead of reporting a failed restore, and Reset no longer leaves Calm feed offering to bring back the setup from before it. The YTDLnis rows say to put its package name in Send links to another app, which is a text field, not a switch. The store region row names Override SIM details when that's the switch that's off. Start the day at takes 06:00 or 0600, which it used to read as 23:00. A long restore result now stays on screen long enough to read. Two playback rows moved out from under Auto-advance, where they didn't belong, and the Feature Gate Lab says when Undo stops covering a preset.
* **TikTok:** Saves now connect only to the server address Hushfeed checked. It used to check that a download's host was a public address and then let the phone look the name up again as it connected, so a name that switched to a local address in between could still have been reached. Each download now goes straight to a checked address over HTTPS, and the certificate is still checked against the original name. Checked on a phone with a video, a photo, subtitles and an animated sticker.
* **TikTok:** Mute feed videos works on TikTok 47.1.3 now, and on 47.0.3 it silences photo posts with a sound too. TikTok gets the next videos ready before you reach them and starts most of them by a route the mute never heard, so it never knew which videos were the feed's. It follows the feed's own list now, and a video is already silent when you swipe to it. It stays silent when you come back after watching the same video from a DM, and after scrolling a long profile grid or search results. On 47.0.3 ordinary videos went quiet before, but only because TikTok's request for the sound was turned down.
* **TikTok:** The README has a troubleshooting entry for unfollowing many accounts at once. TikTok's own Manage view on the Following list does it with checkboxes, and the 10,000 follow limit is TikTok's server rule, which no patch can change.
* **TikTok:** The feed filter table in a diagnostic report now has a line for each route that hands the feed one cached video at a time: the cache chain, the video TikTok slips in when playback lags, and the one it delivers when the feed reaches its end. They never had a line, so a report couldn't tell whether they ran.
* **TikTok:** In the SIM country preset list, the chosen preset's operator line now sits level with every other row's, at any font size. The mark beside the chosen country made its title taller and pushed that line down, and with large text a long country name wrapped only once it was chosen. Every row now keeps a column for the mark, like Android's own choice lists.
* **TikTok:** The README says why a one-photo post saved with TikTok's Download video keeps TikTok's watermark. The server holds no video for such a post, only the photo and its sound, so TikTok renders one on the phone with its logo, the creator's handle and an end card. Download image gives the clean photo.
* **TikTok:** Exported diagnostics keep room for the events that matter. Playback quality wrote a line for nearly every video, since gear names and bitrates change each time, and a busy export was almost nothing else. It now writes one line per mode and size ladder.

## 0.64.0 (2026-09-28)

* **TikTok:** Hiding buttons in the right column now works on videos opened from a creator's profile, a hashtag or a sound too (#47). Those play in a screen of their own, which the hides never reached, so only the feed lost its Like, Save or Share button.
* **TikTok:** Hide TikTok Shop also hides a LIVE that is selling while it streams (#46). Only Hide LIVE videos took those away before, and that hides every LIVE.
* **TikTok:** Tapping Pause Hushfeed in the settings search no longer closes TikTok. It opens the main settings page with that row picked out.
* **TikTok:** The settings search finds rows that stay greyed out until another switch is on, such as Auto-advance session limit. Their page says which switch that is, where the search used to say nothing matched.
* **TikTok:** Default playback speed, Subtitle language, the delay before controls hide and the edge press step grey out while the switch that uses them is off, and say which switch that is. A greyed choice puts that note on its own line under its value.
* **TikTok:** Undo last restore or reset keeps whatever it replaces, so pressing it a second time brings back the changes you made after the restore. A restore that also wrote Feature Gate Lab rules retires the Lab's own Undo, which would have taken those rules back.
* **TikTok:** A restore that kept one of your download folders is no longer undone at the next start when TikTok closed before it finished tidying up.
* **TikTok:** The daily budget's countdown keeps running after you come back from messages, a creator's video or Hushfeed's settings, and Wait for a tap after a return only stops the feed when you actually left TikTok. A hold that ended while TikTok was in the background takes its panel down when you come back.
* **TikTok:** With the feed muted, opening the comments or coming back to TikTok no longer pauses the music another app is playing.
* **TikTok:** Clear all on the Inbox's suggested accounts works on TikTok 47.1.3. It found nothing to press there.
* **TikTok:** A story saved with its sound is no longer reported as failed when only the sound ran out of space or time, so a retry doesn't make a second copy.
* **TikTok:** The already-saved choice opens on the screen you're looking at, a video opened from a profile or search included. It used to open behind it, where nobody could see it, and hold the save until you went back to the feed.
* **TikTok:** With Remove watermark off, Automatic saves keep TikTok's watermark even when Hushfeed makes the save for its details, progress row or saved-video check.
* **TikTok:** With only Show download progress on, a video Hushfeed can't fetch as one file goes to TikTok's own save instead of being refused. A save that fails no longer announces "Stopping after this file".
* **TikTok:** Only these caption languages reads three-letter codes such as eng or spa as their two-letter forms. Before, eng matched nothing and hid every English video it was meant to keep.
* **TikTok:** A clip shorter than a second can now count as seen. It never played to the one-second mark, so it kept coming back.
* **TikTok:** The like and follow confirmations let a tap through when TikTok hasn't said which video is playing, as on some photo posts. The button did nothing at all there.
* **TikTok:** The Feature Gate Lab says why a change was refused, for example that none of the chosen gates had an override to reset, and shows its rule limit, instead of asking you to try again. A reviewed preset waits until the whole gate list has loaded rather than refusing as not matching.
* **TikTok:** In the Feature Gate Lab the list ends above the selection bar at any text size, and the bar drops its hint line at large text. Switch Access, Voice Access and TalkBack's actions menu can open and select gates.
* **TikTok:** Diagnostic reports leave out network addresses, your phone's own IPv6 address among them, TikTok's US and EU server names, and the video ids in saved file names. With SIM spoofing on, debug logging no longer records your real carrier and country.

## 0.63.0 (2026-09-27)

Includes the changes developed in 0.62.0, which wasn't published as a separate bundle.

* **TikTok:** Diagnostic messages remove quoted credentials, headers and account identifiers before reaching system logs, the in-memory buffer or crash files. Long inputs and exception chains are bounded, and a long dotted hostname no longer overflows the redactor's stack.
* **TikTok:** Opening links in an external browser preserves ordinary sites' `target` parameters. Only TikTok's known link-safety routes unwrap that parameter. Lookalike characters in a hostname no longer pass that check.
* **TikTok:** Ghost mode shows whether this process has seen a reporting call and warns when a story-reporting check fails. Clearing diagnostics or toggling the switch doesn't erase that warning. A diagnostics row sits beside the switch. Blocking a local call still doesn't verify TikTok's viewer list (#39).
* **TikTok:** Delayed budget changes survive a failed save and can be retried. Tightening a limit saves the new value and cancels its scheduled change together. A restore saves the delayed changes with the rest of its settings, and recovery restores both. A failed edit keeps its dialog open instead of claiming it was scheduled.
* **TikTok:** A settings transaction that throws puts every live value back. A queued screen-time reminder also checks that its original screen is still active and the feature is still on before leaving TikTok.
* **TikTok:** Failed settings and Lab exports remove the file created by the picker. If the provider refuses cleanup, the message points to the folder you chose. Backup actions stay disabled while a write is running, including after the settings page is recreated.
* **TikTok:** Structured Lab edits keep their draft after a failed save. Discard returns to the last successful save, newer edits survive an older save finishing, and untouched null fields keep their original values. Reset refreshes the fields to the value it restored.
* **TikTok:** Multi-file saves stop on a storage refusal or deadline instead of trying another mirror or counting the last file as an ordinary skip. Counted audio saves preserve the same reason.
* **TikTok:** Sticker saves check free space in their destination before writing and again while PNG, GIF and video output grows. Running out of space removes the unfinished output and keeps an older saved file intact.
* **TikTok:** Share-action and button checklists keep your choices open when a save fails. Apply saves the whole selection together, and you can retry without choosing everything again.
* **TikTok:** Clear seen videos waits for storage before announcing success. Failed clears keep the record and earlier Undo, and completion from a previous account can't offer recovery on the current account.
* **TikTok:** Undo and Unblock controls respect Android's requested time to act. The Calm feed preset keeps its heading and action visible at larger text sizes in both themes.
* **TikTok:** New release receipts check the source commit and clean source snapshots embedded during the build. A dirty build stays ineligible after its files are restored, even with a matching timestamp. Ignored source files also make it ineligible. Equivalent checkout line endings keep the same metadata. Builds whose before-and-after source bytes differ stay ineligible. Previously published receipts are accepted only when their exact recorded bytes and bundle identity match the verified archive.

## 0.62.0 (2026-09-27)

Source changes included in the v0.63.0 release.

* **TikTok:** Installation instructions name the current published bundle and distinguish it from the 98-patch source build. Removed the inherited donation button and corrected the Android verification advice.

* **TikTok:** Show download progress adds a bar for a single video save, including Automatic quality (#27). Known-size video transfers show a percentage; preparation and unknown-size transfers keep a busy indicator. It starts off and announces the start once to a screen reader.

* **TikTok:** Downloads can save a TXT file with the video's caption and source details. An optional saved-video check offers Open or Save again before making another copy, even when the post no longer supplies a download link. Start a video filename template with `{creator}/` for a folder per creator. The switches start off and existing templates keep their current folders.

* **TikTok:** The Feature Gate Lab leaves more room for gates on small screens (#42). Its title fits in the toolbar, the warning opens from the icon beside Apply overrides, and tabs can scroll sideways at larger text sizes. Search and the override switch stay on screen.

* **TikTok:** The Feature Gate Lab now has Reviewed presets in its menu. The first restores See translation on 47.1.3 by setting both required gates together. You can review the values before applying and undo the whole change. A preset for another TikTok version won't apply.

* **TikTok:** New patch, Mute feed videos. It adds a movable button beside the block control, and a switch under Playback, that play the feed without sound while the phone's volume stays where it is. Music from another app keeps playing while the feed is muted, since TikTok no longer takes the audio over for it. DMs, stories and LIVE keep their sound, and the button says Muted or Sound on to a screen reader.
* **TikTok:** With a playback quality set, a video's quality list is read once instead of on every switch. TikTok asks for it through two getters and reads the next video's list in between, and Hushfeed kept only the last one, so each switch parsed the whole list again. It keeps the last four now.
* **TikTok:** New switch under Feed screen, Hide the status bar in LIVE rooms (#38). A LIVE then fills the screen up to the top edge, camera cutout included, and TikTok's buttons at the top move up into the space the bar left. The status bar comes back as soon as you leave the LIVE or switch apps. Hide the status bar never reached LIVE rooms, since TikTok opens them as a separate screen. It's part of Hide video overlays.
* **TikTok:** New row in Feed filter, Only these caption languages. List the languages you understand, like en, es, and a video whose original caption is in another language is hidden. It goes by the caption TikTok marks as the original, so a video with no caption, only translated captions, or a tag that isn't a language always stays. It takes effect without a restart, leaves profile pages alone and counts what it hides in the diagnostic report. The section it sits in is now called Words, countries and languages.
* **TikTok:** Restoring a settings backup no longer takes a download folder that can't hold what it's for. A backup from before the three download folders were split names one folder for everything, and a Pictures folder there used to become the video and sticker folder too, while those saves quietly went to DCIM/TikTok. Now that folder keeps what you had, and the restore says so in its banner. Undo still puts every folder back as it was.
* **TikTok:** A number setting greyed out by another switch shows "Turn on ... first." on a line of its own. It used to run straight on from the current value, as in "Current: Off Turn on Auto-advance videos first."
* **TikTok:** Plainer words in the Feature Gate Lab and the number editors. A gate's source line says whether TikTok declares it and whether it has a current value, where it used to say "generated registry" and "current cache". Player config is now Video player settings and Save JSON is Save report. An empty number field shows the range it takes, and an empty maximum in a range reads No limit.
* **TikTok:** The block symbol Block from comment puts beside a comment's heart is easier to hit. It was about 40 by 24 dp, and a tap now counts anywhere in a 48 dp square around it where the row has blank space. A tap meant for the heart, the comment text or the next comment still goes there, and Easier comment likes keeps working beside it. How far it reaches depends on the row: on a Galaxy S22 it's about 45 by 35 dp, because the comment text sits right above it.
* **TikTok:** Ghost mode also holds back the play report TikTok sends when a story plays (#39). Every story view went out twice: once as a story view, which Ghost mode already blocked, and once as an ordinary play count carrying the story's id, which it didn't, and a second account could still see you in its viewer list. Feed videos still send their play reports as before. This hasn't been checked with two accounts yet, so the diagnostic report now has a "story play stats" line saying whether the report was held back.
* **TikTok:** Hide videos you have already seen has a new row, Count a video as seen after. Set a percent and a video only counts once you've watched that much of it, so five seconds of a ten-minute video no longer hides it at 50%. Zero, the default, keeps the old rule of a few seconds. A video TikTok gives no length for still counts after two seconds, and a short clip never needs its last second.
* **TikTok:** The settings search and the Feature Gate Lab search match text the same way now: case, accents and punctuation don't matter in either, so "pre roll" finds "Pre-roll".
* **TikTok:** Saving, restoring, resetting and undoing settings, and the Calm feed preset, tell you how it went in a banner at the bottom of the settings window instead of toasts that vanish. A restore that left some settings alone says so in the same banner, and a change that needs a restart has a Restart now button.
* **TikTok:** On a phone whose blocked creators, hidden creators, creator exceptions or blocked caption words list grew past the limits added on 2026-09-14, restoring a backup, resetting settings and undoing now work again. Before, all three failed with the generic try-again message. Saving a backup names the list that's too long instead of writing a file no restore would accept.
* **TikTok:** The Feature Gate Lab works from the TikTok build you have installed. It shows that build's own gate list and names it in exports and backups. When TikTok updates, it keeps the overrides whose gates didn't change, turns the rest off and tells you how many. Before, it treated 47.1.3 as 47.0.3 and carried every override across unchecked.
* **TikTok:** The Lab's "first caller" and its SettingsManager observations name the right code again. Reads without a default had stopped being recorded after the first read with one.
* **TikTok:** The diagnostic report has a LIVE BUTTON section that says what decided the LIVE button in the feed's corner each time: the Hide the LIVE button switch, TikTok hiding it because LIVE has a bottom or top tab, or Hushfeed keeping it because that tab was taken off (#28).
* **TikTok:** New patch, Stay on the video in full screen. When a video ends in TikTok's full-screen viewer, you stay on that video instead of being moved to the next one, and the next-video countdown doesn't show. Turn it on in Hushfeed settings > Playback. Swiping still moves on. The viewer has an auto-next of its own, which Auto-advance and Stop video looping never reached.
* **TikTok:** People and apps hidden from the share sheet one per line are hidden from its contact row too. That row only split the list on commas, while the rest of the sheet also split it on line breaks.
* **TikTok:** Share sheet tools looks through TikTok's screens once per layout pass instead of four times. It runs on every layout of the main screen, share sheet open or not.
* **TikTok:** The block symbol that stands in for the thumbs down on comments is drawn in the same grey as the comment's name and time, so it shows on TikTok's dark comment sheet. It used to take its colour from the phone's theme, which TikTok's own dark mode doesn't follow, and came out almost black on black.
* **TikTok:** Hide videos you have already seen keeps a separate record for each TikTok account on the phone. Before, a second account inherited everything the first one had watched, and could have seen that list. Clear seen videos now names the account it forgets for. The record from before this update doesn't say whose it was, so it hides nothing until you add it to an account with the new Add older seen videos to this account row, which shows up while there's anything to add.
* **TikTok:** Saves refuse a download address in any block the internet's registry sets aside for testing, documentation, benchmarking or network equipment, not just private and local ones. TikTok's servers never use those, so a link that points there is not a TikTok file. On a mobile network that only has IPv6, saves from IPv4-only servers keep working.
* **TikTok:** Your own profile shows your newest posts again (#35). TikTok loads a profile's posts in the same kind of list as the feed, so the feed rules reached profile grids too. With Filter location-tagged videos on, recent posts with a place tag were taken off your own grid and only older ones were left. Your own posts now stay wherever TikTok shows them.
* **TikTok:** A profile you open keeps all of its videos. Minimum views, likes and the other count limits, Hide seen videos and the rest of the feed rules used to hide posts there as well, so a small creator's page could look nearly empty. Profiles, their favorites and their reposts now lose only ads, the same as the rest of the profile page.
* **TikTok:** The camera and microphone indicator stays up for as long as TikTok records. Before, the orange diamond only flashed at the start of a story recording, because TikTok records a story's sound in its own native code, which the patch never saw. On Android 11 and newer the mark now also reads what Android itself reports for TikTok's camera and microphone, the same thing the status bar's privacy dots show. On older Android it works as before.
* **TikTok:** On a TikTok version where a patch's hook can't be written the way the patch asks, that patch now stops with a message naming the method, instead of applying and then doing nothing. Morphe's patcher could leave a hook call out without a word when a method has more than 16 registers. It never happened on 47.0.3 or 47.1.3, and now it can't happen silently on a later version either.
* **TikTok:** Save story works again when you press and hold a story. It looked up TikTok's story player by names from TikTok 46.2.3, and neither 47.0.3 nor 47.1.3 has them, so holding a story showed "Open the story again and try once more" and saved nothing. It finds the story by TikTok's own class names now, which don't change between versions.

## 0.61.0 (2026-09-26)

A large release. Hushfeed supports TikTok 47.1.3 as well as 47.0.3 now, and it carries every fix made since 0.60.0. Copy comments without username works from the comment menu again, folding a foldable no longer jumps to another video, and sticker saves read TikTok's own sticker data. The settings screens also had a long polish pass.

* **TikTok:** Save media on a sticker's sheet lightens while you hold it, like TikTok's own Share and Save next to it. It used to be the one button there that showed no press.
* **TikTok:** Save media on a comment sticker reads the sticker from TikTok's own sticker data now, which keeps its names from one version to the next. It used to look first for a field and a class that neither 47.0.3 nor 47.1.3 has, and only reached the sticker through its last fallback. It saves the picture the sheet shows, and if a later TikTok renames that data, Hook status says so.
* **TikTok:** Hushfeed supports TikTok 47.1.3 as well as 47.0.3 (#33). Every patch applies to both, and Morphe Manager lists both versions. 47.1.3 renamed 42 of the screen parts Hushfeed finds by name, so each one is looked up by the name the running build gives it. The feed filter reads 47.1.3's rearranged cold-start cache, AMOLED dark theme knows its gray palette, the corner LIVE button check reads its LIVE mode where 47.1.3 moved it, and the four resource optimizers and the language purger accept its reviewed files, from the universal APK and from APKMirror's split bundle. On the S22 running 47.1.3, the feed, comments, inbox, share sheet, captions and the dark theme were checked.
* **TikTok:** The Feature Gate Lab names its export file in UTC like every other Hushfeed export, so they sort together.
* **TikTok:** A like, comment or share count range restored with spaces around its numbers is read as that range instead of being reset to any.
* **TikTok:** A CAPTCHA check whose type TikTok sends as a number is named by its scene now, not by the word scene.
* **TikTok:** Region spoof no longer works out the country, locale and timezone again on every call, and TikTok asks for them constantly. Share sheet tools, the comment tools, search card filtering, Fit the video to the screen and the Feature Gate Lab look up what they need once instead of on every screen update, and the block button's overlay no longer keeps a closed screen in memory.
* **TikTok:** A blocked-creator pattern that could stall the feed is refused when you type it and skipped, with one note, when it comes in with a backup or from an older list. That covers back-references, a repeat inside a repeated group, and patterns stacked with open-ended repeats. The time limit that was meant to catch these never actually ran on a phone.
* **TikTok:** Rarely, the block, Not interested and sound buttons stayed off for a whole video when it started playing at the moment the feed loaded it. That no longer happens.
* **TikTok:** A restored backup that had a number out of range, like an edge seek of 90 seconds where 60 is the most, is no longer undone at the next start as if the restore had been interrupted.
* **TikTok:** A day locked by the daily budget stays locked when TikTok restarts after the phone's timezone moved forward. Before, swiping TikTok away after the change handed the rest of the day back.
* **TikTok:** A settings search that finds nothing now says that switches from patches you didn't tick in Morphe Manager aren't listed. Fill the screen with the video is one of them, since it comes with Fit the video to the screen, which is off by default.
* **TikTok:** Camera and microphone indicator draws a green square for the camera and an orange diamond for the microphone, so telling them apart doesn't depend on seeing green and orange. The text on Hushfeed's banners over a video is easier to read against a bright frame or TikTok's light comment sheet.
* **TikTok:** In settings, the back arrow is announced as a button, and the search box's clear button, the Lab's switch rows and its Technical details heading show when they're pressed or focused. A chosen SIM preset no longer looks like the row that has focus. When a budget change or Pause is refused, the reason stays on screen, under the number you typed or in the settings banner, instead of a toast that was gone before the time in it could be read.
* **TikTok:** Settings text reads more naturally, with contractions throughout, one way of asking you to restart TikTok and one of saying to try again in a moment. Number rows show their range the way they show the value, as in "0 to 86,400 seconds". The Feature Gate Lab says On or off where its filter said Boolean, tells you what to fix in a bad value or bad JSON, uses a real ellipsis while it loads, and its menu has a title and a Cancel.
* **TikTok:** The settings home's status mark is a rounded square now, on the same corner scale as the cards and icon tiles, and the three quick routes no longer sit on the search card's edge. The Feature Gate Lab's icon buttons show where keyboard or d-pad focus is, which the round ripple they had never did.
* **TikTok:** The page that opens when settings can't load now has a proper title, and Retry is drawn as the way forward instead of a second plain row next to Back. Choice dialogs line their marks up under the title and show Cancel in the dialog's own language. What's new offers Got it and Later and has an icon of its own. When Undo or Restart now fails, the message says which one failed, where it used to talk about undoing a clear either way.
* **TikTok:** A What's new row sits at the top of Hushfeed's settings after an update. It opens the changelog for the version you're on, and for any releases you skipped since you last dismissed it. Later keeps the row for another look, and Got it takes it away until the next release.
* **TikTok:** Hide Play Store update offer is a new optional patch. On the S22, Play changed from Update to Open while Morphe Manager kept showing TikTok 47.0.3. Android won't install a lower-code APK over this one, so leave the patch off if you expect to switch back without removing the app.
* **TikTok:** The settings home has pink section icons, quieter group labels and a warmer active card in dark mode. Search now has its own framed field and a separate results card. Section pages keep pink headings and larger row titles.
* **TikTok:** Repost diagnostics writes the request, TikTok's response status and the next repost-list result into a diagnostic report when logging is on. It keeps the video's ID and any repost note out of the report.
* **TikTok:** Taking LIVE off the top feed tabs now keeps the corner LIVE button available, just as taking LIVE off the bottom bar does. Hide the LIVE button still works on its own.
* **TikTok:** On a foldable with the split comment view, folding or unfolding no longer moves you to another video when Hide seen videos is on. The rebuild for the new width let the filter drop the video you were watching before TikTok could put it back.
* **TikTok:** Camera and microphone indicator shows its mark on TikTok's camera screen. Before, the first time the camera opened the mark was drawn on the main screen hidden behind it, so on a phone it rarely showed at all.
* **TikTok:** Copy comments without username works from the comment sheet's own menu again. Since TikTok 46.9.3 that menu builds the clipboard text a different way, and the option was only reaching Copy in Favorites. Stickers and emoji in the copied text come through as before.

## 0.60.0 (2026-09-25)

A small release with three fixes. Comments that TikTok dropped without a word now go out, the caption no longer shows over the video when Caption above comments is on, and Allow Duet and Stitch covers creators who limit duets on their whole account.

* **TikTok:** Comment send fix, a new patch that's on by default, covers a way TikTok drops a comment without a word. TikTok checks a send against the page opened most recently, and when that page has already lost its screen, the check stops the comment and shows nothing, so the text just stays in the box. The check now gets the comment panel's own screen in that case. The diagnostic export names the page that had none, so a report from a phone where comments still don't post says whether this was the reason.
* **TikTok:** With Caption above comments on, the caption over the video goes away, since the whole caption now sits at the top of the comments. It used to show in both places. Hide the caption still hides it on its own.
* **TikTok:** Allow Duet and Stitch now answers the creator's account-wide choice as well as the video's own. TikTok checks both, and on the S22 a video that allowed anyone still had no Duet entry because its creator's account allowed only people they follow back. With the switch on, Duet and Stitch both show on those videos now. Whether TikTok's servers accept the upload is still up to them.

## 0.59.0 (2026-09-25)

The biggest release so far, with everything built since 0.58.0 and this week's bug reports. The seek bar that TikTok's experiment took away is back (#16), Watch history works with the browser guard on (#16), the tab names above the feed can go (#32), a video can fill the screen (#29), the corner LIVE button survives a filtered bottom bar (#28), split comment view follows the fold (#26), and the Products block in search has a switch (#21).

* **TikTok:** Taking the LIVE tab off the bottom bar no longer takes TikTok's LIVE button off the top of the feed too. TikTok hides that corner button while LIVE has a bottom tab, and it checked its own list of bottom tabs, which still had LIVE after Filter bottom tabs removed it, so LIVE had no way in from the feed (#28). Hide the LIVE button still hides the corner button when you want it gone.
* **TikTok:** Fill the screen with the video, a new switch under Playback beside Fit the video to the screen, crops a video until it covers the whole window, for the phones where TikTok leaves a black strip under a 9:16 video (#29). Turning either of the two switches on turns the other off.
* **TikTok:** Hide the tab names above the feed, a new switch on the Feed tabs page, takes the Following and For You names off the top of the feed. Swiping between the feeds and the search button stay (#32).
* **TikTok:** Show the progress bar now beats the two TikTok experiments that hide the seek bar. One hides it on every video except paid content for whoever the server puts in that group, which left the bar gone from the whole feed but present in the drama player, and it can come and go between restarts (#16). The other hides it on any video the server hasn't marked as draggable. Both ran before Hushfeed's switch was asked.
* **TikTok:** Wait a day to loosen the budget, a new switch on the Screen time page, keeps a change that loosens the daily budget until the day starts over, while one that tightens it applies at once. A higher budget or none, a shorter hold, more times to open the feed anyway, another hour for the day to start and the switch itself going off all count as loosening. The row says what it will change to and at what time. Restoring a backup, resetting and Undo follow the same rule, and Start today over and Pause Hushfeed are refused while the switch is on, since each would take the budget off at once. A restore on a locked day now leaves every budget setting as it is, where before it could hand a locked day a looser budget.
* **TikTok:** More opens the comments, a new switch under Comments, makes tapping more under a video open its comments with the whole caption as the first row, instead of spreading the caption over the video (upstream #156). A tap on the caption text does the same. The row is TikTok's own, the one its comment list shows when an opener asks for it, so the caption keeps its links and its own more and Hide. Caption above comments, the switch beside it, does the same for the comment button. Photo posts keep TikTok's own page for this, which already puts the caption above the comments, and ads keep TikTok's handling. Both switches are part of Comment tools, which now brings Double-tap controls with it to open the comments. On the S22 more on a video opened its comments with the caption on top in place of TikTok's Tako panel, the comment button did the same with the second switch on, a photo post still opened TikTok's post page, and with the switches off everything was TikTok's again.
* **TikTok:** Three new switches under Feed screen > Gestures let a long press on Comment, Share or Favorites play the video at the hold speed, the way it did before TikTok gave those buttons menus of their own (upstream #87). With its switch off a button keeps TikTok's menu, which is the emoji row on Comment, the quick share row on Share and the new collection offer on Favorites. The hold is TikTok's own and shows its speed banner. Letting go doesn't count as a tap, so it doesn't open the comments or save the video. A press TikTok won't hold, because the finger slid off the spot or a second finger landed early on, opens the button's menu as before. The switches need the Long press row on TikTok default and Seek from the edges off, since both take the right side of the screen for themselves, and they grey out otherwise. They're part of the Long-press controls patch. On the S22 a hold on each switched-on button played at 2x with no menu and went back to normal speed on release. A hold on Favorites left the video unsaved, and a tap on Comment still opened the comments. Every menu came back with its switch off, and with edge seeking on.
* **TikTok:** Show poll results before voting, a new switch under Comments, draws a creator's comment poll with its bars and percentages before you pick an answer. The counts already come with the comments whether or not you've voted, and TikTok only waits for your vote or the poll's end to draw them. Tapping an answer still votes, and no answer is marked as yours until you do. It's part of the Comment tools patch. On the S22 a poll the test account hadn't voted on arrived with every answer's count and the total, and with the switch on it drew 72, 26 and 2 percent of 276 votes with no answer marked, where TikTok's own row showed only the answers.
* **TikTok:** Show TikTok's feed buttons, a new switch on the Feed tabs page, puts TikTok's own play and pause button and its previous and next buttons on the feed. TikTok builds that row into every feed page for people who use a screen reader, and shows it only while one is running. With the switch on it shows for everyone, and a change to the switch shows as soon as you're back on the feed. TikTok's own analytics note the row and each press on it, as they do for screen reader users. TikTok's layout lets each button take focus on a tap, which a screen reader never runs into, so the patch also makes each one act on the first tap instead of the second. Previous on the first video of For You reloads the feed, as it does with TalkBack, unless Keep For You on a pull down is on. On the S22 each button acted on the first tap whichever one was tapped before it, and the row stayed hidden with the switch off.
* **TikTok:** Keep a paused video paused, a new switch on the Screen time page, stops TikTok from starting a video you paused when you come back to the app. TikTok plays it again twice as it returns, once as the feed resumes and once as the video's surface is rebuilt, and both plays go through one method, which now turns down a play of that video for three seconds after the return. Your own tap plays it as usual, and so do TikTok's feed buttons and a screen reader's play action. A video you left playing starts again the way it always did, whether you went to the home screen or to another TikTok screen such as your messages. It needs Android 10 or newer, the first version that lets the app read the video before TikTok stops it, and the Block author button patch, which carries the other playback switches too. On the S22 a paused video stayed still after a trip to the home screen, played at a tap, and one left playing picked up again on return. TikTok doesn't draw its play button over the kept video, so it shows as a still frame.
* **TikTok:** Open TikTok on, a new choice on the Feed tabs page, picks the tab TikTok starts on when you open it from its icon: For You, Following, Friends, Inbox or Profile. TikTok default leaves the pick to TikTok. A notification or a link still opens where it points, and a TikTok that Android restores goes back to the page it was on. A tab hidden on the same page isn't opened, so TikTok starts where it would have, and neither is Inbox without a signed-in account, since TikTok sends a start there to its login screen. On the S22 a start from the icon opened on Inbox, Profile, Friends, For You and Following in turn, a hidden Friends or Following tab left TikTok's own pick in place, and a start carrying a notification's tab still went to that tab.
* **TikTok:** Two new switches on the Feed tabs page keep For You where it is. Keep For You on a Home tap stops a tap on Home, or on the For You tab while For You is showing, from reloading the feed, and Keep For You on a pull down does the same for a pull at the top of the feed. Both are off by default, and the other feeds refresh as before. A kept refresh ends the way TikTok ends one it skips, so the Home icon turns back from its refresh arrow and no spinner hangs. TikTok's own reloads, like the one after you block the creator on screen, still go through. Keep For You on a pull down also covers the Previous action TikTok's accessibility tools offer at the top of the feed. On the S22 a Home tap and a pull each kept the video playing with its switch on and reloaded the feed with it off.
* **TikTok:** Confirm feed interactions has three new switches under Feed screen, for a comment's like, a story's like and TikTok's quick repost from the bar under a video. Each is off by default. The first tap arms and a second one within four seconds goes through, the way the Follow and like switches already work, and removing a like stays immediate. A comment's like asks from the heart in the comment list, from a double tap on the comment and from the like on a comment's photo page. A repost from the bar asks whether it comes from the bar's repost button or from the repost guide TikTok shows on the same bar, since both send it at once. On the S22 a comment's heart and a story's heart each armed on the first tap, liked on the second and unliked at once, and with the switch off one tap liked as before. The photo page's like and the quick repost bar didn't turn up for the test account, so those two haven't been seen on a phone yet.
* **TikTok:** The diagnostic export now counts the strips TikTok shows above a caption and the banners under a video, by kind, whatever the logging switch says. A kind is TikTok's own key for it, such as a place or an effect, and never what the strip says. Some phones get strips that promote something else, like a Lemon8 link or a "Find ..." search, and a switch that hides them needs those keys, so an export from such a phone now carries them. With diagnostic logging on, a banner kind that first showed up before logging went on used to stay unnamed until TikTok restarted. It's named the next time it shows up now.
* **TikTok:** AMOLED dark theme paints the right colors on TikTok 47.0.3. TikTok added a color ahead of its dark palette in 47.0.3, which moved every name in it one along, and the patch kept rewriting the 46.x names. So it turned TikTok's pink-red accent, an orange and two see-through overlays black, and it left four of the five dark grays as they were, among them the one fifty of TikTok's layouts use. Each build now has its own palette, read off that build, and on a build nobody has read yet the patch stops with nothing changed instead of guessing. On the S22 the only colors the rebuilt table changed were those five grays, and TikTok ran in Dark as before.
* **TikTok:** Hide mini dramas now takes TikTok's Short Drama block out of search results as well. That's the header with an Explore button, a row of category chips and a strip of dramas that a search for "short drama" opens with. The block isn't one of the results the other search filters read. TikTok streams the Top results in pieces and builds it afterwards as a server-drawn card, so it's now caught where its row is drawn and folded away. On the S22 a search for "short drama" showed the block with the switch off and went straight to the videos with it on. The diagnostic export also names each server-drawn card a search shows, by its template and never by what it says, so a report of another block can say which one it was.
* **TikTok:** Disable telemetry now stops the analytics uploads themselves. With the switch on, TikTok's AppLog SDK still sent its queued packs to the log hosts (session starts and ends, and events that reach it past the entry points the switch covers). Its forward worker posted copies of them to a second path and a backup, and the install SDK checked in once a start with the advertising id, carrier, SIM region and time zone in the query. On the S22 that came to 10 requests and 134 KB over 20 videos, about as much as with the switch off. Three walks since the fix sent none. The pack send answers as if the pack had gone out, so the SDK clears its queue and nothing piles up for later. The forward send stops before its post, and the SDK has already dropped those rows by then. The check-in answers success without leaving the phone. The SDK's priority uploader gets the same treatment. It sent nothing on the S22, but a setting it stored earlier could turn it back on. Device registration and the settings fetch aren't touched. With debug on, the network report also writes the path of each request to a log host into the log, so a leftover line in the report can be traced to the SDK behind it.
* **TikTok:** The comment keyword filter now reaches comments TikTok shows translated. It judged a comment's text when the page loaded, and a translation landed in that same text afterwards, so a blocked word the original never had could still show up: with batch translation on, 2 of 14 shown comments on the S22 held the blocked word. A translation is now judged as it arrives, before TikTok shows it, and again whenever a translated comment is drawn, and a comment whose translation holds a blocked word collapses out of the list. TikTok's own translations count too, and Comment tools does this on its own, without Translate comments. A collapsed comment comes back as soon as the filter is off. Comments shown in their original language are judged as before, and a voice comment's translated transcript is left alone, like its original one.
* **TikTok:** The Bottom tabs to keep row under Feed tabs now says to open the feed once while TikTok hasn't been seen loading a tab yet, and its dialog says the same above the two tabs every install has. The note was written for both tab editors but could never show for this one: Home and Profile are always offered, so the two rows counted as tabs already seen, and the row said All loaded tabs when nothing had loaded. Select every tab waits for a seen tab, as it does for feed tabs.
* **TikTok:** The Long press row under Feed screen > Gestures gains Find the sound on YouTube Music. A long press opens YouTube Music's search for the sound's title and artist, the pair TikTok's own sound page shows, so the track can be saved to a playlist there. TikTok used to offer that and took it away. Nothing is sent anywhere else and no account is involved. Without YouTube Music installed, or for a sound with no title, a short toast says so and nothing opens, and so it does for the video's own original sound, which YouTube Music won't have.
* **TikTok:** New Creator exceptions row under Feed filter, beside the block lists. Name a few accounts by handle or id and their videos stay when only a preference filter would have hidden them: stories, photo posts, location tags, AI labels, verified accounts, playlists, age, length and the view, like, comment, favorite and share ranges. Ads, blocked creators, words, sounds and countries, paid and Shop content, LIVE, mini dramas, Series and seen videos still hide them. The row names any entry a block list also holds, since that creator stays hidden, and refuses a pattern or a display name before it saves. One that arrives in a settings backup is ignored and named on the row. The list rides along in a settings backup with the other rule lists.
* **TikTok:** Hide the Tako bubble now takes the Ask Tako pill off the search results page too, the first tab on every search that the switch had left standing. It isn't one of the served tabs but a view TikTok builds into the strip, so it is hidden by the id name its text keeps once the page is built. The other tabs keep their order, and switching it off brings the pill back after a restart. The served tab list is filtered as well, for a build that ever serves the tab as data, and with diagnostic logging on the export lists the tab keys the strip held.
* **TikTok:** A save of three or more files (a post's original photos, a story's photos, a video with its sound and subtitles) now shows a running count in a row above the save banner, with a Cancel that lets the file under way finish and leaves the rest. A file that fails is skipped instead of ending the save, and the result says how many landed, how many were skipped and whether the rest were cancelled, or stopped for lack of space or time. The row is read out once when it appears, once when Cancel is pressed and the result once at the end, not on every file.
* **TikTok:** Saving a live photo from a comment now keeps its motion. TikTok's own Save photo hands its downloader the still alone, so the clip never landed (upstream #169). Once TikTok has the comment and the tapped photo in hand, Hushfeed fetches the clip and saves it beside the still as an MP4, with a banner of its own. A plain photo comment saves as before. Needs the Downloads patch. Not yet seen on a phone: no live-photo comment turned up on the test phone, so the clip's own landing there is unverified.
* **TikTok:** Remove ads now reaches the cold-start TopView, the full-screen ad TikTok can open with. Its ads never pass through a feed list the filter sees: the feed fetch hands them straight to TikTok's splash service before the list is filtered, which is why an earlier reset of that list at filter time reached nothing. The list is emptied as TikTok reads it, so TikTok's own "nothing to preload" path runs, the one it takes on every start served no ads. The diagnostic export carries a TopViewPreload line saying the route ran, ads or none. The test account is served no TopView, so the empty handoff was checked on a phone and the ad itself gone was not.
* **TikTok:** The speed menu's toast now names the speed you picked. Choosing a custom speed such as 2.5x played at 2.5x but the toast said "Playing at 2x speed", because TikTok labels a custom speed with its nearest built-in and only 0.5, 1.5, 2 and 3 have their own. It reads "Playing at 2.5x speed" now, and TikTok's own speeds are unchanged.
* **TikTok:** New switch under Screen time, Leave when TikTok says time is up. TikTok's own daily screen-time reminder (the full-screen card once the limit under TikTok's Time and well-being settings is spent) sends the app to the background instead of waiting to be dismissed. Coming straight back within a few minutes leaves the reminder on screen, so the limit and the switch itself stay reachable. (upstream #2)
* **TikTok:** Every full patch run on a fixture now checks the rebuilt resource table against TikTok's own, across all twelve of its packages. A resource that no longer resolves, a lost value or style item, a missing file or a reference to nothing fails the run and names the id. That's the kind of failure behind upstream's crash on a layout in TikTok's search package (upstream #84). The report also lists every value the patches changed, which is how the dark theme's wrong palette on 47.0.3 turned up. On all five fixtures, TikTok 46.2.3 to 47.0.3, every patch applies and every one of the 80,632 to 83,292 resources resolves.
* **TikTok:** Every runtime view ID Hushfeed uses is now tied to TikTok 47.0.3 code, including share actions, comment dislike controls and caption text. Each one is also told apart from the other ids the same TikTok class or layout loads, by the view it is set on, cast to or handed to, so a build that moves a name to a neighbouring view fails the test instead of passing it. Every lookup in the code is traced back to that table too, whatever helper, loop or constant carries the name, along with the package it resolves under.
* **TikTok:** In-app browser privacy guard now protects external websites without breaking Activity center, Watch history, shop checkout or CAPTCHA. Its Privacy switch has clearer wording and still starts off.
* **TikTok:** New Pause Hushfeed switch at the top of Hushfeed settings. From the next start TikTok runs as if it weren't patched, which answers "is it Hushfeed?" without turning things off one at a time, and your settings stay put for when you switch it back. Hushfeed also pauses itself after TikTok crashes within a minute of starting three times in a row. If the settings can't be reached at all, an empty file named `hushfeed-safe-mode` in TikTok's folder under Android/data pauses it too.
* **TikTok:** New switch on the Feed filter page, Hide TikTok Shop in search, takes the Products block and single product cards out of search results. No account here is shown that block, so if it still turns up for you, a diagnostic export made right after that search now says what kind of card it was. (#21)
* **TikTok:** Foldable split comment view now follows the fold while TikTok is open. Unfolding past your width setting refreshes the feed so comments can sit beside the video, and folding back refreshes it again. Before, it only took effect if TikTok was started already unfolded. (#26)
* **TikTok:** AMOLED dark theme works on an .apkm again when Morphe Manager merges it. It used to refuse every merged bundle, because rebuilding the desktop CLI's merge made TikTok crash at launch. Manager's merge keeps TikTok's own file paths and loses nothing, so the dark theme now refuses only an APK whose paths clash with the rebuild, which is what the desktop CLI's merge still produces. (#21)
* **TikTok:** Hide comment popup ads now stops the brand animation itself. On 47.0.3 it only turned off TikTok's analytics report about the animation, so the animation still played. TikTok's own celebration when you post a video's first comment still plays. It now tells TikTok's own celebrations from a campaign's by where the surprise comes from rather than by what is written on it: a surprise sent with a comment page stays only when the page was fetched for one of TikTok's own scenes, a surprise sent with a comment you posted stays only when it is the first-comment kind, and the replayed first-comment milestone always stays. Before, a campaign's surprise with no trigger word written on it played, and a celebration with one written on it didn't. Comment pages and posting a comment were checked on a phone, where no surprise came either way. The celebrations and the campaign case were checked against TikTok's code, not with a real campaign or a first comment on a video that had none. Tell us if one still plays for you, or if a celebration stops.
* **TikTok:** AMOLED dark theme now blackens 47.0.3's sheets and panels too, the comment panel and the share sheet among them, along with menus and pop-ups. They had stayed dark gray, because 47.0.3 paints them from a different color than 46.x did.
* **TikTok:** New Swipe-left controls patch. A left swipe on a feed video opens the creator's profile in TikTok, and the new Swipe left row under Feed screen > Gestures can make it do nothing or open the video's comments instead. Only a swipe toward the profile changes. Swiping right still opens TikTok's side panel, a photo post keeps its own swipe between pictures, and where TikTok doesn't page to a profile itself (the Inbox, your own profile) nothing happens. Changes take effect without a restart.
* **TikTok:** Video download quality now takes effect on TikTok 47.0.3. Hushfeed looked for the list of qualities under the name older versions used, found nothing there, and every chosen quality fell back to TikTok's own save. A saved video is also never taken from ByteVC2, TikTok's own format that other players can't open, and at the same size H.264 comes before HEVC. When only ByteVC2 is left, TikTok's own save runs as before, and when the size you picked comes only as ByteVC2, TikTok's own watermark-free file is saved if it says it's closer to that size, rather than a smaller copy.
* **TikTok:** Save the original photos works on TikTok 47.0.3. A photo post's Download never reached the step Hushfeed took the save from, so TikTok's own save ran and no original was fetched. Now "Download image", a photo saved without asking, and the photos picked in TikTok's selection sheet save the originals under your filename template, numbered by their place in the post. A live photo you chose still comes down as TikTok's video. TikTok's picker for posts with several photos goes through the same step and saves only the ones you pick, but it wasn't tried on a phone yet. One photo now says "Saving one original photo".
* **TikTok:** Turning automatic clear display off while it had the controls hidden no longer keeps TikTok's top bar, and its search button, away on the next videos. TikTok brought its other controls back by itself, but Hushfeed still thought they were hidden. The same went for a feed item that comes without a video id after a cleared video, with clear display automatic or remembered.
* **TikTok:** Clear display, TikTok's own or the automatic one, no longer lifts the daily hold. It hides TikTok's tab bar, and Hushfeed took that as you leaving the feed, so once the budget ran out the hold's panel came down and the sound came back. Now the panel stays up. With Remember clear display patched in, TikTok's controls come back as the panel goes up, so the Inbox and Profile tabs the panel points you to are right there, and nothing clears again until the hold is over. Without it, TikTok's own clear display bar stays uncovered under the panel, and its X brings the tabs back. The same mistake hid Keep captions in clear display as soon as the controls cleared. The caption stays now, but never on top of the hold's panel. The Block and Not interested buttons still go away with TikTok's controls.
* **TikTok:** A finished save now offers a way to it. Saving a video at a chosen quality, a story, original photos, a profile picture, a sound or an original sound used to flash a two-second toast with a folder path. The same words sit on the six-second banner now, and its Open button hands the saved file to a viewer. One banner per save: a video or story that carries its sound as well announces the sound with a plain toast beside the banner. Saves made of several photos open on the post's last one. Where there is nothing to open, or no app on the phone opens that kind of file, the toast says so.
* **TikTok:** A bigger caption size no longer splits words in the middle. The caption kept the width TikTok measured for its own smaller text, so at size 28 a word could break across two lines ("conditio" over "ns."). It lays out wider now, up to TikTok's own caption width so it stays clear of the buttons on the right, and longer lines wrap between words. Only a single word wider than that still breaks. At any size the caption's box now fits its text, with no empty band beside it, and the size is applied to the caption itself, so other text TikTok draws the same way, such as a video's description, keeps TikTok's size.
* **TikTok:** The daily hold pauses the video on TikTok 47.0.3 again. The panel went up but the video kept playing behind it, because the pause Hushfeed called by its TikTok name had turned into something else in 47.0.3. The hold now finds TikTok's own pause and resume where TikTok's feed uses them, and Open the feed anyway picks the same video back up.
* **TikTok:** The two pause switches work on TikTok 47.0.3. Silence the feed while comments are open did nothing there at all, sound included, because 47.0.3 draws the comments into the app's own screen rather than a window of their own, and a window was what the switch watched for. It goes by the comment sheet itself now. Under "Tap to start the feed" after coming back to the app, the video kept playing, since 47.0.3's player plays on when something else asks for the sound. Both switches now pause the video with the same TikTok pause the daily hold uses, and it carries on from the same spot when the comments close or you tap. A video that was already paused when the comments opened stays paused, turning the switch off with the comments still open leaves the video quiet until you tap it, and the comments switch's description says it pauses now.
* **TikTok:** New Network request report patch, off by default. It counts the requests TikTok's own API client sends, grouped by domain and by kind (API, log, monitoring, messages, LIVE and the rest), and adds them to the diagnostic export. Video and image downloads and other companies' SDKs use connections of their own and aren't counted, and the report says so. Nothing about the requests changes.
* **TikTok:** Feed filter adds Hide mini dramas, for TikTok's short drama episodes and the cards that promote them. It's off by default. Hide Series already caught drama episodes, since TikTok sells them as Series, but this one leaves other Series alone. (upstream #155)
* **TikTok:** Feed tabs adds Hide the unread badges on the bottom tabs, for the red counts and dots on Inbox, Profile and any other bottom tab. The inbox itself still shows what came in.
* **TikTok:** Playback adds Hide TikTok's Auto scroll button, for readers who want videos to advance on their own but not TikTok's own Auto scroll action in the video panel.
* **TikTok:** Playback adds Speed while you hold the video, so the hold gesture and its pull-down lock run at 1.25x to 3x instead of TikTok's fixed 2x. TikTok's own banner, lock guide and lock toast say the speed you picked.
* **TikTok:** Leaving Hushfeed's settings search with the Back arrow now takes the keyboard down with it. It used to stay up over the next page until Back was pressed once more.
* **TikTok:** The four optional cleanup patches accept APKMirror's 47.0.3 bundle too, the way they accept 46.2.3's. A merged 47.0.3 bundle carries the full APK's files less 92 armeabi-v7a libraries, byte for byte, and 27 of the 64 language packs. Every file they empty is still checked first. (#21)
* **TikTok:** Hushfeed's settings now survive TikTok's own crash recovery. After three crashes in a row at launch, TikTok deletes every settings file it doesn't recognize, and that used to take all of Hushfeed's settings with it, Lab rules and the Calm feed undo included.

## 0.58.0 (2026-09-21)

This week's bug reports, two requested switches and Turkish.

* **TikTok:** Hide series no longer empties creator profiles. TikTok 47.0.3 tags every ordinary profile video as episode 0, and Hushfeed took that for a series. Only a real episode number counts now. (#20)
* **TikTok:** Hide the Tako bubble now also removes the Ask Tako button floating over the search page, including the version paired with a Voice button. (#22)
* **TikTok:** The Save media button is back on comment stickers. TikTok renames its button class with every build, so the button had quietly gone missing since 46.7.3. Hushfeed finds the sheet's buttons by their layout now. (#23)
* **TikTok:** Cut the in-app browser off from TikTok now says plainly that it also stops Activity center and Watch history from responding. If Watch history ignores your taps, turn that switch off under Privacy. (#16)
* **TikTok:** Feed screen adds Hide the Report button on videos, for the flag button some regions get above the creator's picture. App adds Hide search rewards, for the points banner under the search box and the coin counter floating over results. Both are off by default. TikTok doesn't show either one in our region, so they were checked against the app's code and not on a phone. Tell us how they behave for you. (#21)
* **TikTok:** Hushfeed's settings now come in Turkish. It's a first pass, and corrections from Turkish speakers are very welcome. (#13)

## 0.57.0 (2026-09-20)

Block a creator and move on.

* **TikTok:** The one-tap block button now skips to the next video as soon as TikTok confirms the block. A small Unblock button appears at the top left for two seconds instead of the large bottom banner. It always reverses the original block, even after the video changes.
* **TikTok:** If you've already scrolled or left the feed while a block is being confirmed, Hushfeed won't move you again. Failed requests don't skip a video. Other Undo notices keep their existing recovery time.

## 0.56.0 (2026-09-20)

Give video controls the space used by suggested searches.

* **TikTok:** Feed screen adds Hide the search bar below videos. It removes the suggested-search strip above the bottom tabs so video details and side controls can use that space. The top search button and suggestions above comments stay unchanged. It's off by default and needs a restart.
* **TikTok:** Includes the independent location badge hiding and location-tagged video filtering from 0.55.0, plus Full screen button hiding and Easier comment likes.

## 0.55.0 (2026-09-20)

Choose which video overlays you want to see.

* **TikTok:** Feed screen adds Hide the Full screen button and Hide location labels. Each has its own switch inside Hide video overlays. Hiding a location card doesn't filter the video or change location permissions. Full screen hiding leaves playback and automatic rotation alone. Both options are off by default and need a restart.
* **TikTok:** Location hiding covers multi-place badges too. Filter location-tagged videos is a separate option under Feed filter > Ads. It skips videos with place badges, whether or not they're paid ads. Hiding a badge doesn't erase the location markers used by the filter. Both controls are optional.
* **TikTok:** Includes Easier comment likes from 0.54.0. The heart can receive taps in nearby blank space without making comment rows taller. Enable it under Comments.

## 0.54.0 (2026-09-20)

Comment hearts are easier to tap.

* **TikTok:** Comment tools adds Easier comment likes. It extends the heart's touch area into nearby blank space without enlarging the icon or adding space between comments. Text, Reply and neighboring controls keep their own areas. Swipes and interrupted presses don't turn into likes. It's off by default and needs a restart.

## 0.53.0 (2026-09-20)

Give the comment list more room.

* **TikTok:** Comment tools adds Compact comment header. It removes the comment count, sort and close buttons, plus the suggestion area above them, including its empty space. Comments and the reply box stay available. Use Back or swipe down to close the panel. Headers with list-navigation tabs stay visible. The switch is off by default and needs a restart.
* **TikTok:** Fixed a local release check that could block a new tag even though its source commit was already on GitHub. Update-index changes still require a published, verified bundle.

## 0.52.0 (2026-09-20)

Comments get their own suggested-search control, and search settings now say where they work.

* **TikTok:** Comment tools adds Hide search suggestions above comments. It removes TikTok's "Search: ..." banner above the comment count without removing comments, their controls or Hushfeed's separate search box. It's off by default and needs a restart.
* **TikTok:** Search controls now distinguish the feed button, Inbox button, LIVE drawer, image-search prompts, main search-page suggestions and searching within loaded comments. Poll hiding also explains that it removes voting cards and ended poll results.
* **TikTok:** Fixed the Hushfeed source metadata that made Morphe report "The remote metadata file is unavailable." Refreshing the source is enough. The release check now rejects timestamps Manager can't read.

## 0.51.0 (2026-09-20)

Commission posts now use the same location-affiliate signal TikTok checks before showing its disclosure.

* **TikTok:** Remove feed ads catches location videos marked "Creator earns commission" even when TikTok leaves the shopping and standard ad fields empty. It filters the video before display, without matching English text. Ordinary location tags aren't enough to remove a post.
* **TikTok:** Paid partnerships and branded content are included in Remove feed ads. The separate paid-partnership switch remains available, and the related controls now sit together under Ads.
* **TikTok:** When For You is the only remaining top tab, its title and underline are hidden. The feed and Search stay available.
* **TikTok:** App settings can hide the purple rewards shortcut beside Add friends on Profile. This belongs to Hide floating promotions and is off by default.

## 0.50.0 (2026-09-20)

Settings failures are recoverable, movable controls stay reachable and the largest settings catalog no longer ranks searches on TikTok's UI thread.

### Interface and accessibility

* **TikTok:** Large translated settings titles now keep words whole at enlarged text sizes. The title adapts within a limited range while Android's chosen font scale remains unchanged everywhere else.
* **TikTok:** Movable feed controls now stay clear of status bars, navigation insets, display cutouts and TikTok's bottom tab row. They recheck those boundaries whenever the window geometry changes.

### Performance

* **TikTok:** Feature Gate Lab now precomputes searchable gate text and performs fuzzy ranking on a cancellable worker. Typing a newer query immediately invalidates older results, so a slow search can't replace what the reader just requested.

### Reliability

* **TikTok:** A failed settings write now keeps the live value aligned with the value that will survive a restart. Concurrent writes can't cross its rollback window, and feed actions show a failure instead of a false success or Undo state.
* **TikTok:** Settings restore now rejects fractional and overflowing backup schema numbers instead of truncating them into a supported version.
* **TikTok:** Session budget resets now invalidate older queued writes, so one test can't quietly restore a spent budget after the next test has cleared it.
* **TikTok:** A first push of a branch now checks its complete resulting tree. A documentation-only tip or stale tracking ref can no longer hide a code path from the local gates, and the script contracts follow the current TikTok 47.0.3 target.
* **TikTok:** Guarded S22 acceptance now checks the focused window on Android's default display and sends every input to that same display. TikTok on another task or display can't authorize input, and the helper still resolves Windows `adb.exe` and evidence paths correctly under WSL.

## 0.49.0 (2026-09-20)

Settings feedback is clearer, easier to recover from and more consistent across every page.

### Premium settings polish

* **TikTok:** Invalid number, text, range, creator, folder and Feature Gate values now show an inline explanation inside the editor. The field keeps focus, announces the problem once and clears the message when typing resumes.
* **TikTok:** Text cursors, selection handles, picker frames and list edge effects now use the Hushfeed palette instead of leaking TikTok's theme into the settings screen.
* **TikTok:** Clear seen videos and Start today over now offer Undo in a ten-second settings banner. The action survives page changes and remains at least 48 dp for touch and accessibility input.
* **TikTok:** Restart-gated changes now offer Restart now in the same banner while keeping the pinned restart row for later. Returning a value to what TikTok already runs no longer asks for a restart.
* **TikTok:** Feed and bottom tab pickers now join the persistent restart state correctly instead of showing a restart message and then losing it.
* **TikTok:** Forty-one labels and descriptions now use one naming system across Feed filter, Feed screen, Inbox, Playback, Region, search and the share sheet. German, Spanish, Indonesian and Brazilian Portuguese were updated with them.
* **TikTok:** The three home shortcuts now become full-width rows at Android's first large-text step, so Privacy and Screen time stay intact instead of breaking in the middle of a word. The full settings matrix was rendered again in dark and light themes, large text and mirrored layout.

## 0.48.0 (2026-09-20)

Feed filter now has a reversible starting point for a calmer feed.

### Calm feed

* **TikTok:** Calm feed turns on six focused filters together: feed ads, Shop posts, LIVE videos, LIVE replays, promotional music and paid partnerships.
* **TikTok:** Applying the preset saves the previous values first. Restore puts that exact setup back, even after TikTok restarts or after one of the six switches is changed by hand.
* **TikTok:** The preset leaves stories, photo posts, AI labels, creator rules, keywords and time limits alone.
* **TikTok:** Its status card shows when the preset is on or has been adjusted. The action is a real 48 dp button, stacks at large text sizes and is translated with the rest of Hushfeed settings.

## 0.47.0 (2026-09-20)

The settings home is faster to scan and easier to use with touch or a screen reader.

### Settings

* **TikTok:** A new status card confirms Hushfeed is active and shows the installed Hushfeed and TikTok versions. Diagnostics opens directly from the card.
* **TikTok:** Feed filter, Privacy and Screen time now have direct buttons beside Search at the top of the settings home.
* **TikTok:** The new actions use real button roles, visible focus and press feedback, and touch targets of at least 48 dp. They stack instead of clipping when text is enlarged.
* **TikTok:** The settings home screenshot has been refreshed from the tested native Android view in both theme and accessibility checks.

## 0.46.0 (2026-09-20)

All 91 patches now support TikTok 47.0.3.

### TikTok 47.0.3

* **TikTok:** Hushfeed is the first row in Settings and privacy again. TikTok 47.0.3 changed how that menu is assembled, and the patch now moves the existing row instead of creating a duplicate.
* **TikTok:** Downloads now follows the actual comment-image watermark draw when TikTok moves nearby MIME and pending-state strings.
* **TikTok:** Feed filter now handles TikTok's separate golden-cache and offline-cache delivery methods. Hide already seen videos follows the same repaired boundary.
* **TikTok:** Playback speed now finds the player boundary by its behavior after TikTok obfuscated both method names.
* **TikTok:** Empty comment pages and ordinary posts without a survey no longer appear as broken hooks in Diagnostics.
* **TikTok:** Block P2P video relay, Remove LIVE extras, Remove content credential and card scanner assets, Remove creation tools, and Remove unused language packs now use reviewed 47.0.3 file inventories.
* **TikTok:** Feature Gate Lab now treats 47.0.3 as its target. Saved rules from an older target stay disabled until they are reviewed.

## 0.45.2 (2026-09-19)

One settings search fix found during the TikTok interface audit.

### Settings search

* **TikTok:** Opening a search result and pressing Back now keeps the query visible. Before this release the filtered result list stayed on screen, but the search box looked empty.

## 0.45.1 (2026-09-19)

Two fixes for TikTok 46.9.3, one of them a crash (#12).

### Fixes

* **TikTok:** On TikTok 46.9.3, Feed filter no longer closes the app when the Following feed loads. That build renamed where the feed keeps its videos, and the filter now finds them under either name.
* **TikTok:** On TikTok 46.9.3, a download with no clean address falls back to the video's own playback address again, the way it does on 46.2.3.

## 0.45.0 (2026-09-18)

TikTok 46.2.3 from APKMirror's bundle download, an .apkm file, now patches cleanly (#9).

### Fixes

* **TikTok:** The four optional cleanup patches now accept APKMirror's 46.2.3 bundle. They refused it before, because a merged bundle carries fewer files and only 25 of the 64 language packs. Every file they empty is still checked byte for byte first.
* **TikTok:** AMOLED dark theme now refuses an APK merged from a bundle instead of building one that crashes at launch, because rebuilding a merged bundle's resources loses about 1,400 of them. Use the plain APK from APKMirror if you want the dark theme.

## 0.44.0 (2026-09-18)

Separate share lists for profiles and LIVEs, and two fixes from a patching report (#9).

### Share sheet

* **TikTok:** A profile's share sheet and a LIVE's share sheet can now hide a different set of actions from a video's. Each has its own checklist under Hushfeed settings > Share sheet, and until you save one it hides whatever your video list hides, so nothing you set up before changes.

### Fixes

* **TikTok:** Downloads no longer fails to patch on TikTok builds where the download step's code is larger than usual. A reporter's 46.8.3 lost Downloads to this.
* **TikTok:** When one of the four optional cleanup patches meets a TikTok APK it hasn't checked, its error now says nothing was removed and what to do next: untick it, or patch the full APK from APKMirror. A split bundle (an .apkm file) can cause this.

## 0.43.1 (2026-09-18)

A packaging fix. No patch changed.

### Fixes

* **TikTok:** The 0.43.0 bundle was published without its patch code, so Morphe Manager showed zero patches for the source and could not build anything. This rebuilds it correctly, with all 91 patches. If you updated to 0.43.0 and saw no patches, update the source again.

## 0.43.0 (2026-09-18)

One new patch, from a feature request.

### App

* **TikTok:** A new Use system font switch draws TikTok's text in your device's own font instead of TikTok Sans. The icons, the gift animations and the @ and # glyphs keep their own fonts. It is off by default and needs a restart. Switch: Hushfeed settings > App.

## 0.42.0 (2026-09-18)

Two ad and Tako surfaces that slipped past the feed filter, both reported on 0.40.0 and traced to routes the earlier hooks never saw.

### Feed filter

* **TikTok:** Ads no longer appear while you scroll a creator's videos from their profile. The profile's own video pager was asking a separate ad endpoint and splicing the answer between the creator's videos, so those ads were in none of the lists the filter already cleaned. That request is refused now while Remove feed ads is on.
* **TikTok:** The "Ask" bar that sat under some videos is gone with Hide Tako AI on. It looked like a Tako feature but was one of TikTok's bottom banners, so Hide Tako AI now covers it along with the floating bubble, and its switch says so.

## 0.41.0 (2026-09-18)

A polish release. No patches were added or removed. Most of it makes the settings screen and the Feature Gate Lab read as one app, with a round of accessibility fixes and a changelog Morphe Manager can finally read.

### Morphe Manager

* **TikTok:** Every patch description now says where its switches live, for example "Switch: Hushfeed settings > Feed filter.", so choosing a patch in Manager tells you which page to open afterwards. Eight descriptions that opened with "Adds an option to" now start with what the patch does.

* **TikTok:** Manager's update screen can show what changed. It only reads changelog headings that carry a date, and every release since 0.23.0 had none, so updating Hushfeed showed an empty list. Every heading here has its date now, and each entry names TikTok so Manager can tell the update touches it.

### Settings search

* **TikTok:** Search keeps what you typed when the phone rotates or the text size changes, and the keyboard no longer covers the last results or the empty-state message.

* **TikTok:** Tapping a search result scrolls to it with a little of the page still showing above it, and the row flashes briefly so you can see where you landed. With TalkBack on, focus lands on that row too.

* **TikTok:** The clear button is a drawn cross at the same stroke weight as the back arrow, with a ripple when you press it. It used to be a typed × character that grew with the text size. The query and the result count now share one left edge.

### Settings pages

* **TikTok:** A setting set to zero says what zero means. Caption text size reads "TikTok's size" instead of "0 points", and Clear display delay reads "No delay".

* **TikTok:** Opening a page slides it in from the side you're moving toward, and Back slides it out the way it came, instead of the same fade both ways. Right-to-left languages mirror the motion, and with animations turned off in Android's settings, pages switch instantly.

* **TikTok:** Page titles break between words at large text sizes instead of mid-word, so German no longer shows "Kommen-tare". Menu summaries are 14sp like every other summary, not 12.8sp.

* **TikTok:** Disabled text in the light theme is readable now, at about 4.5:1 against the page where it was 3:1, and a disabled row's arrow dims along with its title.

* **TikTok:** Dialogs share one corner radius, one set of margins and one 20sp title size. The SIM preset and tab dialogs had tighter corners, and the gate recording dialog's title was 28sp.

* **TikTok:** The About row says it opens github.com in your browser, and it no longer shows the arrow that means another settings page.

* **TikTok:** Browse in a download folder dialog keeps what you typed. The dialog stays open under the folder picker, a folder you pick fills the field, and a folder Hushfeed can't use, like one on an SD card, shows the reason under the field rather than in a toast after the dialog has closed.

* **TikTok:** The hidden creators editor labels its search and add fields the same way, and adding a creator now confirms it the way removing one already did. With only a few creators in it, the list shrinks to fit instead of leaving a tall empty band.

* **TikTok:** Clear seen videos becomes "Undo clearing seen videos" while you can still undo, says the undo only lasts until TikTok closes, and shows how many videos a clear would forget.

* **TikTok:** The Hook status row on Diagnostics starts with a green check when every hook found its target and an amber warning sign when something is missing, and TalkBack reads that state before the sentence.

* **TikTok:** Cancel buttons follow Hushfeed's language instead of the phone's. The tab picker explains why it's empty until TikTok has loaded the feed, and its Select every tab button waits until there are tabs to select.

* **TikTok:** Choosing a SIM preset no longer toasts what the row already shows, a speed list that can't be saved shows the reason under the field, and the Hide feed save button row explains how it differs from the Save entry in the checklist above it.

### Feature Gate Lab

* **TikTok:** The Lab looks like the rest of settings. Its title matches every other page title, row titles are 16sp, the section titles on a gate's page match the settings categories, and the scope row underlines its choice the way the source tabs above it do.

* **TikTok:** The account warning on the Lab and the sensitivity note on a gate's page sit inside a card with a warning sign, instead of hanging between controls as loose orange text.

* **TikTok:** A field that can't be saved on a gate's page is marked where it is, with the cursor moved into it, instead of reported in a toast. The custom value dialog says "unverified" once, and asks for a whole number rather than naming a data type.

* **TikTok:** Holding a gate to select several is easier to discover. The selection bar says how, the count is announced as it changes, and TalkBack offers "Select" on each row.

* **TikTok:** The gate list scrolls without reading every row's saved override from storage. Leaving the Lab no longer repeats "Restart TikTok to apply this." after each change already said it, and an export's file name follows the TikTok version the Lab targets.

### On the video

* **TikTok:** The ring that arms a Follow or Like confirmation is white with a dark inner edge, so it shows on TikTok's red buttons, and arming it vibrates. It used to be the same red as the button it circled.

* **TikTok:** Block, local hide, block sound and Not interested vibrate when tapped, like the share sheet's confirm step. The comment block used the long-press vibration for a tap.

* **TikTok:** When automatic advance reaches its limit, the notice offers Keep going, which starts a fresh run from the video you're on. Before, the only way on was to open settings and raise the limit.

* **TikTok:** While a block or a sticker save is running, the button stays readable instead of fading into the video, and a screen reader hears "Blocking" or "Saving". The sticker Save button also follows the sheet's own light or dark look.

* **TikTok:** The comment search box lines up with the comment text instead of running edge to edge. The daily hold panel keeps its text away from the screen edges at large text sizes, and the tip about dragging a control shows only until you've moved it once.

* **TikTok:** The CAPTCHA notice stays up long enough to read. On Android 13 and newer, copying a link no longer shows Hushfeed's toast on top of Android's own, and a failed hand-off to a downloader app names the app instead of its package name.

### Accessibility

* **TikTok:** The range dialog reads its Minimum and Maximum labels once, as part of their fields, and the keyboard no longer autocorrects a value like "1.5M" into something that won't parse.

* **TikTok:** Pressing Done on a dialog's keyboard saves it just like tapping Save, including on number keyboards that send Enter instead of Done.

* **TikTok:** The hold panel's two actions are announced as buttons. A gate's Technical details heading says whether it's expanded, once rather than twice, and each info row on a gate's page is read as a single item.

### Wording

* **TikTok:** Failure messages end with what to do next. A failed save now says "Allow storage for TikTok in Android settings to save sounds." rather than only that permission is missing.

* **TikTok:** The feed is called the feed everywhere, not "the homepage", "Home feed" or "TikTok home", and TikTok LIVE is spelled LIVE throughout.

* **TikTok:** Remove creation tools says plainly that the Create tab stops working, rather than that it may.

### Diagnostics

* **TikTok:** The diagnostic export lists Hide Tako AI whenever one of its hooks attached, even with the switch off. Before, the Tako family could be missing from a report entirely.

* **TikTok:** When every mirror fails a download, the error keeps the cause of each attempt instead of only the last one.

## 0.40.0 (2026-09-17)

* The settings menu is four groups instead of one list. Your feed holds Feed filter, Feed tabs and Feed screen. Watching and sharing holds Playback, Screen time, Comments, Downloads, Share sheet and Inbox. Privacy and system holds Privacy, Region, App, the Feature Gate Lab, Diagnostics and Backup and restore, and About sits on its own at the end. The "YOUR EXPERIENCE" label over a single card of fourteen rows is gone. Five pages have shorter names: Interface is Feed screen, Feed navigation is Feed tabs, Comments and translation is Comments, Region settings is Region, and App behavior is App.

* Screen time is its own page. The daily video and time budgets, the reminder, the hold and its fade, the budget label, the lock, the day's start and the passes all sat at the bottom of Playback under a "Daily budget" heading. They have a page of their own now, with "Do not start the feed on returning" under a Focus heading at the top of it.

* Backup and restore is its own page. Back up, Restore, Reset and Undo used to be the tail of Diagnostics, so anyone who patched without the diagnostics patch found them on a page named for something they didn't have. The Feature gate recorder row moved the other way, off the menu and into Diagnostics, where it stops being the one row without an icon.

* The Feed screen page is laid out by where things sit. Right column first, with one checklist row over the six hides and the counts (its second line says what is hidden, "Hidden: Like, Share", instead of seven rows each saying "Hide the ... button"), then Video info, Around the video, Popups, Captions, Screen, Clear display and Gestures. The three long-press rows that lived under Player on the App page sit beside the Long press row now, and the playlist bar, event badge and inserted cards rows moved here from Feed filter, since they hide things drawn around a video rather than videos.

* Feed filter reads in the order people ask: Kinds of post with Remove feed ads first, Limits, Creators and sounds, Words and countries, Seen videos, then one Advanced switch. The four player buttons (block, local hide, block sound, Not interested) moved here from the App page, next to the lists they add to.

* The App page keeps what is about the app: Layout, Player, Search (with Hide search suggestions), Profile (Keep the Favorites tab) and System. Downloads has Files, Video, Photos and stickers, Long press, Subtitles, Hand-off and Offline videos headings, and Comments has Reading, Hiding and Blocking.

* Every menu icon is its own picture. Feed screen and App shared the sliders glyph; Feed screen draws the feed now, a screen with the right column down its edge, Screen time is a clock and Backup and restore is a box with an arrow rising out of it. The App page has Search and System headings so its last four rows stop reading as part of Player.

* A Privacy page. Everything that decides what TikTok learns sits in one place now. Disable analytics and tracking and Ghost mode moved there from App behavior, the three link rows came with them, and each of the device-access patches below has a switch of its own. Before this the seven device patches had no row anywhere, so there was no way to see they were installed and no way to turn one off short of patching again.

* Seven patches that stand between TikTok and the phone: Block contact list access, Block installed app scanning, Location access governor, Device privacy guard for clipboard reads, Resource and battery governor for the motion sensors, In-app browser privacy guard, and the Camera and microphone indicator. All but the browser guard are on as soon as the patch is chosen. The browser guard stays off until you turn it on, because TikTok's own web pages are built on the bridge it cuts, the shop checkout and the CAPTCHA page among them.

* Three of those patches said more than they did, and two did nothing at all. The contact blocker matched the plain call form only, and a five-argument ContentResolver.query is always the ranged form, so it intercepted zero call sites and applied anyway. The installed-app blocker watched getInstalledPackages, which TikTok 46.2.3 never calls. The app list is read through the launcher enumeration, which is what it watches now, and a check for one named app is left alone so opening another app still works. The device privacy guard claimed to block local network scanning and the battery governor claimed to throttle preloading. Neither did, and both descriptions now say what the code does. Every one of these patches reads both call forms now and refuses to apply when it finds nothing to intercept.

* The camera and microphone indicator draws the dot it promised. A green dot sits in the top corner while TikTok has the camera open and an orange one while it records sound, both when both, on whichever screen is in front. It follows Camera.open, camera2's openCamera and AudioRecord.startRecording, and goes when the camera is released or the recorder stops. The first version wrote a log line and nothing else.

* Block P2P video relay strips TikTok's peer-to-peer CDN libraries so the phone isn't used as a relay for other people's video. Stop on-device AI profiling returns early from the Pitaya inference engine's start. Remove content credential and card scanner assets now also empties six Pitaya model libraries per ABI, the LIVE casting feature and the ART log monitor probe. It leaves libbytemonitor, libprofiler and libAndroidPitayaCore alone: other libraries in the APK link against those, and an emptied one fails their load. The first cut emptied libbytemonitor too, which libbytebench needs, which the whole video editor needs, so the Create tab killed the app until the S22 showed it.

* Maximum views per comment joins Maximum views per like under Feed filter, for hiding videos with a lot of views and few comments.

* The six right column switches (hide the like, comment, favorite, share, avatar and music buttons) work again on 46.2.3. Since 0.35.0 they looked for the video cell under the long-press layer, which on this build sits beside the column rather than above it, so the walk found no buttons and hid nothing. It starts from the cell's own root now, which holds both.

* Feed button size makes the right column's icons a quarter larger for anyone who finds them small. Under Feed screen. The first cut offered 1.5x and 2x as well and scaled the whole button, and on the phone that did nothing: TikTok animates those buttons itself and writes its own scale back on every frame. The setting scales the icon inside each button now, from the icon's base so it grows upward, and puts the size back before each frame TikTok draws. Past 1.25x an icon runs into the next button, so the two larger sizes are gone, and a stored 1.5x or 2x reads as 1.25x.

* A feed check asked from a new window no longer takes its answer from a Home tab that belongs to an older one. Anything drawn only on the recommendation feed could otherwise show up, or stay away, on the wrong screen for as long as the old window lingered.

* The share prompt that pops up after a like can be hidden. With the switch on, the bubble asking you to share a video with friends never appears. Under Feed screen, off by default. Upstream #22.

* The four feed overlay controls are drawn by one class at one stroke weight. Sound was a Unicode note in whatever font TikTok picked, local hide was a bold ×, and not interested was a thin dash; only the block symbol was drawn. They all draw through `OverlayGlyphDrawable` now, at the same 2dp stroke and the same radius fraction, so they look like one set.

* The remembered speed can be turned off. It was always on, so a reader who wanted TikTok's per-video reset had to drop the whole playback speed patch. With the new switch off, each video starts at 1x and a manual choice from the menu lasts for that video only. Under Playback, on by default.

* "Enter a number. The previous value was kept." is gone: the dialog stays open for a correction now, so no value was kept. Five Playback summaries that opened with "Off by default." lead with what the feature does instead; the switch already says whether it is on.

* The asset strips patch the newer TikTok builds again. Remove content credential and card scanner assets knew one reviewed set of files, 46.2.3's, and 46.7.3 and 46.8.3 ship 23 of those 33 paths while 46.9.3 ships different bytes behind 15 of them; Block P2P video relay carried a hash from the wrong build for one 46.9.3 library. A forced patch on any of those builds refused both. Each build has its own reviewed set now, read off the real files, and a test holds every set of every strip against every fixture on the machine before a release is built.

* Holding Like does nothing with Disable the long press repost on. It kept the repost panel away, but it told TikTok the hold was not handled, so Android treated the release as a tap and a hold liked the video: on the S22 the heart went red and the count rose by one. The hold is consumed now.

* "Save the sound as well" saves the sound. Every attempt ended in "The sound couldn't be saved. Try again." and the export said "Download contains no media samples". TikTok's AAC tracks open with an edit list that puts the encoder's priming frames before zero, so the extractor hands the first frames back at a negative time (the S22's files start at -161 ms), and the copy loop read the very first one as the end of the track. A track's lead-in is now taken off every stamp, and when a picture and a sound are put together they move by the same amount, so nothing drifts. Found on the S22 on 2026-09-17.

* A sticker save says where it landed, the same as every other download. It used to say "Sticker saved as MP4" and the reader had to guess the folder. It says the folder now, matching the four other savers.

* The Feature Gate Lab speaks in plain terms. "Getter used", "Loaded", "All actionable", "Applies saved rules at supported getters", "Effective getter result", "TikTok cached value", "Not present in the current cache", "Getter not requested yet" and "Override saved but off" were implementation words that meant nothing to anyone who didn't write the code. The tabs say Seen, All, Overridden; the filter says Not seen; the states say TikTok read it, Not seen yet, Saved, override off; the detail page says What TikTok gets, TikTok's own value, Not read yet; and the master switch says Replace values when TikTok asks for them. The three source-name tables are two: the row badge matches its tab label, and the detail page spells it out.

* Hide Tako AI now covers three additional trigger components. The slot component hooked in 0.39.0 only draws the ask bar on some accounts; on others, TikTok routes through a trigger component in the tikbot package instead, plus a "roof" variant and a detail-page trigger. A reporter's export on 0.39.0 showed the slot hook never firing while the bar was still drawn: the feed-level trigger was the one that ran. All four trigger binds now guard the same way, and the hook status family records whichever path fired.

* Text rows in the settings show their value. The twenty text preferences (Country ISO, Video filename, Blocked caption words and the rest) only showed their description, so the only way to learn what was in one was to open its editor. Each one now carries a "Current:" line underneath, the same as the numeric rows, truncated to roughly sixty characters so a list of blocked words stays one line. An empty field says "Empty", and the value updates after Save, after a backup restore and after Undo.

* Three things in the Feature Gate Lab. The "Unloaded" state label was painted in the disabled color, about 3.7:1 on the dark surface and 3.4:1 on white, under the 4.5:1 floor for 12sp text, on a row that is fully tappable. It uses the secondary color now, and the word carries the meaning instead. Picking a filter used to move the tab and picking a tab used to reset the filter, with nothing on screen saying why either happened; now only the one real contradiction (asking the Loaded tab for unloaded gates) is resolved, and the picker offers only the filters the current tab can answer, so nothing moves. The overflow was a platform PopupMenu in grey next to everything the bundle draws, Undo was offered whether or not there was anything to undo, and the two Resets sat side by side with nothing saying the second also clears the switch, the acknowledgement and the recordings. It is a themed list now, Undo is disabled when there is nothing to undo, and the labels say what they take away.

* Save is reachable in the tab picker at a large text size. The dialog was one column: a title, a two-sentence helper, a list that is never shorter than 220dp, and its own Save and Cancel underneath. At twice the system text size on a short phone that comes to more than the screen, and the panel a custom dialog view sits in clips rather than scrolls, so the actions were what got cut and Save could not be reached at all. Save, Cancel and Select every tab are the platform's own buttons now, which it keeps on screen and shrinks the list for. The SIM preset picker had the same shape and got the same repair, so Clear preset and Cancel stay put too.

* The feed filter notice is a banner over the feed instead of a dialog across it. A scroll used to be stopped dead by a modal whose only action was opening a settings page, and it was written in the code's own words: "No videos remained after Hushfeed applied your feed filters to 3 batches. Most matches: Ads (12)." It now reads "Your filters hid everything TikTok sent, 3 times in a row. Most were Ads (12)", takes no focus, announces itself once, takes itself away after six seconds, and carries a Filter settings action for anyone who wants it. The banner the block controls already used can now carry an action other than Undo, which is what made this possible.

* A web address someone left in a comment can be tapped. TikTok draws comment text as plain text, so the only way to follow a link in a comment was to copy it out by hand, which is what an upstream reader asked for a year ago. The comment text view is found by what it is showing rather than by a resource id, so it is the same answer on any build, and a tap that lands anywhere other than the address still does what it always did: a comment that carries a link does not stop opening the replies. Under Comment tools, on by default, and off in one switch.

* Comment search counts the comments it has seen instead of the rows on screen, and the box has an X that clears it. The count was taken from the rows the list had attached, so scrolling turned "3 results" into "5 results" into "no matching comments" while nobody was searching for anything, and the line is a live region, so a screen reader read out every one of those. A comment is counted once, when it comes into view, and scrolling past it again neither doubles it nor takes it away. The line says "so far", because TikTok pages comments and this has only seen the ones it loaded. The empty state used to tell a reader to clear the search when there was nothing on screen that could; there is now an X at the end of the box, and a screen reader gets a Clear the search action on the field rather than a second stop in the traversal.

* Hide quick comment reactions hides the row above the comment box. The switch only reached the quick comment strip TikTok draws on some videos, so the emoji clusters in the comment sheet stayed put with it on, which the S22 showed. The comment keyboard adds that row through a trigger rather than a visibility write, and the switch now answers the trigger before its own checks run, so the row is gone whether or not the keyboard is up. TikTok builds that slot tree once per session, so the switch takes effect after a restart and its row says so.

* The block control on a comment now looks like one. With Block from comments on, a tap on what was still drawn as TikTok's thumbs down blocked the account, and the only thing that said so was a tint that arrives after the block has already happened. The control draws the block symbol instead, in the same color the icon beside it uses so the row keeps its weight, and TikTok's own icon goes straight back the moment the switch is turned off.

* The register search follows a switch instead of stopping at one. It used to treat a packed or sparse switch as the end of the road and answer with whatever it had proved free before reaching it, which at the top of a method is nothing, so the patch was told there were no free registers and refused to apply. R8 turns a chain of string comparisons into a switch, which means the same method can be an if-else chain in one TikTok build and a switch in the next with nothing about the patch having changed. A switch is now read the way a two-way branch already was: a register is free at the switch only when it is free down every arm and down the fall-through. A payload it cannot read still ends the search where it used to, so an odd one is no worse than before.

* A feed batch that came out empty now says so in the diagnostic export. Hiding livestreams over a page of nothing but livestreams legitimately leaves zero videos, and putting one back would be the switch not working, but from the outside an empty batch and a broken feed look the same: there is nothing to swipe to until TikTok asks for another one. Upstream reported the feed freezing on exactly that swipe. A route's line now carries how many of its batches it handed back empty, so a report about a stuck feed can say whether anything was ever delivered to show.

* A share hook that fails now leaves TikTok's own share sheet alone instead of taking it down. Three of them ran inside the share model's constructor with nothing catching behind them, and the link rewrite replaced a native method outright, so one bad read anywhere in there reached TikTok as a Share button that did nothing, followed by the app stopping. That is what upstream's report looked like from the outside. Each of those boundaries now catches for itself and hands back exactly what TikTok passed in, and a failure that used to be silent shows up in the diagnostic export as a named hook that threw. The confirm step keeps holding a send to a person, but it no longer eats a tap on Repost, Copy link or Save when it cannot tell what it is looking at.

## 0.39.0 (2026-09-16)

* Ads no longer appear while paging through a creator's videos from their profile. TikTok has a mid-roll ad component that waits for the pager to load, then takes the video on screen and an ad, finds the video in the pager and puts the ad in its place. That runs after every list the feed filter reads, which is why issue #2's exports showed a profile list of 184 organic videos with nothing removed while the reporter was looking at an ad: the ad was never in the list. Remove feed ads now refuses that swap, on all four retained builds, and the diagnostic export carries a line for it, so a report can say whether the route ran and what it kept out. The video the ad would have replaced stays where it was.

* Hide Tako AI now covers the "Ask" strip that appears under some videos' captions, the one offering to answer a question about the video. It is drawn by a different component from the floating Tako button the switch already hid, so a reader who turned the switch on still saw it. With the switch on, the strip is hidden before it is filled, and the diagnostic export names it either way.

## 0.38.0 (2026-09-16)

* Hide series no longer empties the feed. TikTok hangs a paid content struct on ordinary recommended videos with its fields left at defaults, and the filter treated the struct being there as the marker, so with the switch on nine of ten videos in a batch were removed and For You never loaded anything. A video counts as a series when it says it is paid content, or when that struct carries a collection behind it: an id, a name, an episode number or the intro flag. Hide playlist videos and Hide AI generated videos had the same fault and got the same repair, so an empty mix or moderation struct is no longer a match. Reported on 0.36.0 with a diagnostic export that named the filter.

## 0.37.0 (2026-09-16)

* The Android 17 audit covers the changes the platform will actually enforce, not just the three on the developer site. A phone running Android 16 already carries eleven compat changes gated at target 37, and three of them could have reached injected code. None does: the payload loads no code from a file, nothing in it subclasses Thread, and a handshake the platform refuses is reported rather than retried, which is the same answer Certificate Transparency gets. Each verdict is held by a test, so a change to any of the three fails the suite instead of a phone.

* Every source notice that cites ReVanced's blocked GitHub repository now carries the GitLab mirror beside it, so a reader following the origin of a file reaches the file rather than a takedown page. The original line stays as the record of which revision the code came from, and a test fetches each mirror once so the next takedown is found by the suite.

* The legacy settings import and export path is gone from the shared library. Nothing but its own test reached it, and it held the last English toasts the translation tables could not cover. Backups go through the settings backup, which still reads the older metra-settings format.

* Inside the patch bundle, thirteen hand-rolled register checks, sixteen hand-written entry guards, two copies of the argument-register helper with different casts, two same-named string helpers with different meanings and four superclass walks are one helper each. Nothing a user sees changes; the next TikTok build that moves a shape is one fix in one file instead of a hunt.

* Auto translate comments no longer walks every field of every comment cell's manager, and the manager's declared methods, three times per cell on the thread that binds the list. The manager's shape is a property of its class, so the first cell of a class pays and the rest read three fields.

* Follow diagnostics lets go of a request when its response is parsed or it fails, instead of holding up to 160 request graphs, bodies and buffers included, until TikTok is killed. A request that never finishes is held weakly, and the readback context expires after its thirty-second window.

## 0.36.0 (2026-09-16)

* The feature gate recorder's report dialog puts Save JSON last with the accent, and Copy report no longer closes the dialog, so the preview stays where it was. A stop with nothing read says so in a sentence instead of showing an empty JSON object, and offers nothing to copy or save. A fresh install's row says no recording has run yet rather than "Last recording: 0 gates", and it has no chevron while a tap starts recording on the spot. Once a report exists, the row offers Start recording or Show last report, so a report closed too soon can be opened again until the next recording.

* Saved gate reports are named by date, like a settings backup, and land in Download/Hushfeed, which is the folder the toast names. They used to be an epoch number under a Morphe folder with a toast naming a Downloads folder the phone doesn't have. When the background pool cannot take the write, Save JSON now says so instead of doing nothing.

* A session hold no longer keeps a once-a-second timer running while TikTok sits in the background. With Lock today's budget on, a hold ran to the reset hour, and the countdown, the layout pass and the budget checks ran every second of it for a screen nobody was looking at. The countdown stops when the app leaves the screen and comes back with it.

* Settings rows keep their drawables when the list rebinds the same view. Scrolling a long page rebuilt the ripple, the card, its mask and a switch's track and thumb for every row that came into view, on every frame.

* A settings backup without a schema number is read as the first schema, and a schema newer than this build reads, or one that is not a number, is refused with the schema reason rather than a generic rejection.

* Every patch applies on TikTok 46.9.3, the build after the three the bundle is checked against. Fourteen patches used to fail there, all for the same reason: 46.9.3's build of TikTok marks methods final and stops merging small lambdas into shared classes, so a getter the patches matched by exact access flags, a lambda they matched by its outlined static shape, a service class they matched by name and a CAPTCHA overload they matched by its parameter list all moved without changing what they do. Feed filter, Hide already seen videos, Hide CAPTCHA popups, Hide the risk control CAPTCHA, Comment sort controls, Comment tools, Advanced downloads, Subtitle tools, Share sheet tools, Translate comments and Disable the long press quick share now find their targets by what the code does rather than by how the compiler shaped it, and the three resource optimizers carry a reviewed profile for the new build. Nothing changes on 46.2.3, 46.7.3 or 46.8.3, which still apply 81 of 81; the declared target stays 46.2.3.

* Playback quality now reaches the player. The setting picked a gear out of the video model's own list and reported it, but TikTok's player builds its models from a different, unfiltered list, so every video still played at whatever quality the player chose. That is what issue #3 saw on a Galaxy A56. The choice is now made at the one door into the player's list, on all four retained builds, and the report names it: a line like "picked lowest_540_1 540p of 5 gears from SimVideoUrlModel#setBitRate" is the player's list, not a bystander's. Checked on a Galaxy S22 with the setting on lowest and then on highest. The hook status row also stops counting a non-adaptive video's empty model as a missing hook, which is what turned "2 missing" into a false alarm, and single-gear lists no longer write a report line each.

* Playback speed and the Feature Gate Lab (and the Feature Gate Recorder with it) apply on TikTok 46.9.3. That build carries the speed menu's list factory twice, two identical copies of one outlined lambda, and a second raw App AB getter next to the first. Both patches used to refuse with "found 2". The speed patch now hooks every identical copy and still refuses when the copies differ, and the Lab holds both raw getters open. Nothing moves on 46.2.3, 46.7.3 or 46.8.3, which carry one of each.

## 0.35.0 (2026-09-15)

* Hide feed surveys no longer empties the profile's Favorites tab. TikTok gives the Favorites page the same view id as the feed survey card, and the overlay hider took every view with that id anywhere in the window. Feed furniture (the caption, the music block, the action column, the survey card, the rail buttons and their counts) is now hidden only inside a feed cell; the tab strip is unchanged. Found on a Galaxy S25 by restoring the settings one group at a time, and the fix was checked there.

* A Feature Gate Lab import that accepted something now reports in a dialog: one line per count, the first rejection selectable so it can be copied, and Undo beside Done. It was a five-clause toast that vanished in 3.5 seconds. An import that changed nothing keeps its one-line toast.

* The Feature Gate Lab's switch rows (Enable overrides and the detail page's Override and Forced result rows) are one screen-reader stop each with the Switch role, and the whole row toggles the switch. They used to read twice and only answer a tap on the switch itself.

## 0.34.0 (2026-09-15)

* A new patch, Comment publish diagnostics, on by default. When a comment will not post, the text stays in the box with no toast and nothing in the log, and TikTok's publish code has a dozen ways to return before the request without a word. The diagnostic report now says whether the send reached that code, what it had in hand (the text's length, the attachments, a reply target, the video), and whether it returned early or handed the comment to the request.

* Reposting a video no longer arms the send-confirmation step. With Confirm before sending on, tapping Repost, Copy link or another share-channel action used to show "tap again to send" and act only on a second tap, because the confirmation gate treated every share-sheet button as a person to send to. It now gates only the recipients you can send to.

* The feed filter table in the diagnostic report now says when a profile or insertion route was handed something that is not a video, and with diagnostic logging on the profile routes list every video they kept with its ad markers (ad, soft ad, raw ad, promotion, commission disclosure). A report about an ad that got through a profile page (issue #2) used to carry the counts and nothing about the ad itself.

* The diagnostic report now has a Feature Gate Lab section: whether overrides are on, each rule with its value, and for each one whether TikTok has read that gate since the app started, from where, and what TikTok had before the override. Until now an export from a phone where an override changed nothing could not say whether the override was live at all.

* Editing the fields of a structured gate in the Feature Gate Lab now shows that a save is pending, offers Discard edits beside it, and says so if you leave with edits unsaved. Before, Save looked the same before and after typing and Back dropped everything without a word. When overrides are off, the page says so above the controls instead of after the last one, with a Turn on overrides action that enables them without leaving the page.

* While a settings backup, restore, reset or undo is running, the four rows on the Diagnostics page go out of reach and the one acting says what it is doing. Before, they looked exactly as they had a moment earlier, a second tap earned "A settings operation is already running", and a screen reader was told nothing at all.

* A settings row greyed out by another switch now says which switch would turn it on, and a greyed switch shows whether it is on or off instead of looking the same either way. On the Region page the two rows that only said in prose that they need Override SIM details are now actually wired to it, so they grey out rather than letting you switch on something that does nothing.

* The SIM country preset list now marks the preset that is already chosen and opens scrolled to it, and a Clear preset action takes the row back to no preset. It used to be sixty identical rows with no sign of which one was on, and no way back short of retyping three fields.

* When a settings dialog refuses a value, the focus now moves to the field that was refused and the reason is read out. Before, the dialog stayed open with the reason under the field while focus sat on Save, and a screen reader said nothing at all.

* Search settings now opens with the search box focused and the keyboard up, the way every other search on the phone does, with a Search key that takes the keyboard down again so the results can be read. Autocorrect is off in that box, so it can no longer rewrite a setting's name into another word.

* The "Blocked" banner with Undo now sits above TikTok's tab bar on the feed and above the input row in the comments sheet, instead of a fixed distance up from the bottom of whichever screen it was on, which put it over the tabs on one phone and on top of the comment box in the sheet. It is drawn on the same scrim, hairline and corner radius as the chips beside it.

* New patch, Keep the Favorites tab, on by default. Two people found the Favorites tab on their profile empty after patching (issue #4): the tab was there, the saved videos were not. TikTok's server can put an account into an experiment that skips building that tab's pages, and forcing either of the two gates behind it on the test phone produced the reporters' page exactly. The patch answers those two reads with the values that keep the tab, the diagnostic report says when it did, and the switch is in Settings, Interface if you'd rather see what the server sends.

* AMOLED dark theme now rewrites the theme style values behind the comments sheet and the share sheet, and one more page background color. Both sheets are painted from style values that never go through the color table the patch rewrote, which is why they stayed TikTok's grey with the patch on. Only dark values are touched, so the light theme keeps its white sheets. On a TikTok build the patch is forced onto, a sheet style name that build does not have is skipped instead of failing the whole patch.

* The feed filter table in the diagnostic report gives the profile detail pager's ad event a line of its own. It used to share the profile grid's line, so a report about an ad seen while watching videos from a profile (issue #2) couldn't say whether that route ran at all or what it took out.

* The diagnostic report now says which playback quality gear was picked and from what. The gear-list path was silent, so a report from a phone whose model strings come back empty (issue #3) couldn't say whether a gear was chosen and then ignored or never offered at all. Hook status lists the gear list getter, and one line per distinct choice names the mode, the gear it settled on and every gear it had to choose from.

* The diagnostic report's feed filter table now counts a profile list or a late insertion that arrived empty. A report from a phone showing an empty Favorites tab used to carry no profile line at all, which read the same as the hook never running, so it couldn't say whether TikTok asked for the list and got nothing back or never asked.

## 0.33.0 (2026-09-15)

* Hide suggested accounts now covers every "People you may like" card, not only the inbox lists. The profile header, the Friends tab and the feed's account cards each build their own list, so the switch used to leave them all standing. Every one of those cards is collapsed as it is bound, and comes back at its own size when the switch goes off.

* A change that needs a restart now leaves a row pinned to the top of every settings page, "Restart TikTok to apply this change" (or how many), and tapping it restarts TikTok. Before, the only sign was a toast that disappeared, so a switch that hadn't taken effect yet looked broken. The rows still waiting say "Restart pending" in place of the usual sentence, a switch flipped back to where it was owes nothing, and the row goes away once TikTok has been restarted.

* Hiding a rail button now hides its count too. Hide like button used to leave the number standing under an empty space, half under the creator's avatar, which looked like the switch had done nothing. Counts under buttons you keep are untouched.

* Clear all in the inbox's suggested accounts now counts as it goes ("Clearing, 12 so far") and a second tap stops it. It used to read "Clearing" and refuse every press for up to eighteen seconds with nothing to say how many were left. Stopping reports how many it dismissed, and the next tap picks up the rest.

* The diagnostic report now says what the CAPTCHA gate saw. A report of a comment or a follow that went nowhere used to carry no CAPTCHA line at all, so nobody could tell whether Hushfeed had seen the request, whether TikTok raised a puzzle, or whether the gate was in that build. The report now names the gate as installed, logs the first write it sees go out and the first puzzle that reaches it, keeps the reason a puzzle was shown next to the line it already kept for one it hid, and puts all of that back after you clear the diagnostics.

* Coming back to TikTok with "Do not start the feed on returning" on now shows a "Tap to start the feed" label instead of a silent, frozen feed with nothing to say why. TalkBack announces it once and can no longer swipe through it to the like and comment buttons underneath. And if you tap Profile or Inbox instead, the stop goes away on its own: it used to stay up over the new page, eat your first tap there, and keep the sound held for a feed you had left.

* The block, hide and sound chips no longer start out on top of TikTok's own avatar, like and share column. Out of the box they sat right on it, so a tap on the creator's face hid the creator instead of opening the profile. They now sit a finger's width to the left of that column. If you have already dragged them somewhere, they stay where you put them.

* When a structured override in the Feature Gate Lab can't be applied, the page now says why, and what to do about it. Before, the status stopped at "could not be applied" and the reason only went to the log. It now sits under the status as a warning line: the field this build doesn't have, the value that can't be copied, the list the override never named, or the type the catalog disagrees on, each with a next step. Translated into German, Spanish, Indonesian and Brazilian Portuguese alongside the English.

* The actions that appear when you hold a gate in the Feature Gate Lab fit the screen now. Reset, Disable, Enable and Cancel sat in a row that couldn't wrap, so at larger text sizes the last of them were squeezed down to a box too small for the word and the labels were cut. In German they don't fit at ordinary size either. They wrap onto a second line instead. Cancel is also no longer painted like Enable, so the action that just puts the selection down doesn't look like the one that writes to every gate in it.

* A boolean gate in the Feature Gate Lab has its own override switch now. Importing a profile brings its rules in with the overrides off, so a gate the file said should be true opened with the switch showing on, and the first tap turned it off and forced false. Two taps to get what the file asked for, and the wrong value was live in between. Turning an imported override on takes one tap, and the switch that says what to force is no longer the same one that says whether to force it.

* A download that was interrupted at the wrong moment no longer leaves an invisible half-saved entry in your gallery forever. The cleanup that was meant to find those gave up as soon as it saw a finished file of the same name, so anyone who had already saved a video called the same thing kept every leftover. It also could not find a leftover at all when the gallery had renamed it, which is what happens when the name is already taken. Both are fixed, and the filename a save reports back is now the one the file actually got.

* The diagnostic report now says what Ghost mode did. It stops TikTok telling other people you looked at a story or a profile, or that you are typing, and until now an export said nothing about any of that, so a report of missing follower counts could not be told from a page TikTok never asked about. The report names each of the three places it answered and what it answered, and says Ghost mode is in the build even on a run where none of them came up. It still names no creator.

* Settings, About now has a Licenses row. It shows the notices Hushfeed and the projects it was built on ask to be passed along, with the full licence a tap away. They were only ever in the repository before, which is no use to anyone holding a patched phone, and the projects this is built on ask that the notices reach that person too. The settings search finds it.

* **Hushfeed now needs Morphe Manager 1.30.0 or newer.** The bundle is built against patcher 1.13.0, which Manager 1.30.0 was the first stable release to ship, and Manager refuses a bundle built against a patcher newer than its own. On 1.29.0 the bundle will not load at all, so update Manager before the next release.

* The build classpath no longer carries a Bouncy Castle release with known advisories against it. Nothing here reaches the app on your phone, which is signed by your own Manager, but the version used to sign and assemble during a build sat inside two advisories and now does not.

* The block, hide and sound chips no longer sit over a creator's profile opened from the feed. Tapping a name or an avatar slides the profile in beside the feed rather than over it, and the feed's bottom navigation, still laid out a screen width to the left, kept telling Hushfeed the feed was showing. The chips were live there too, so a press blocked the video underneath rather than the creator on screen. They hide while the profile is up and come back when you swipe back to the feed.

* The same controls no longer sit over a story. Tapping an avatar with a ring opens the story viewer, which hides the feed rather than sliding it aside, and the controls stayed on top of it. A press there acted on the video you had been watching, not the person whose story was playing.

* Comment translation now tells the Hook status row what it found. The four places TikTok calls into it read fields of TikTok's own objects, and a release that renames one used to leave the switch on with nothing happening and nothing said. The row names the class and the field instead, and it travels in the exported diagnostic report.

* Settings rows show where the focus is. Moving through the list with a keyboard, a d-pad or switch access left nothing on screen to say which row you were on, because the only thing a row painted was a ripple, and a ripple draws focus at a fraction of its own strength. A focused row now carries an accent ring inside the card's corners, and so does the back button in the header. A press still looks like a press.

* A text field in settings shows which one you are typing into. Every field wore the same accent underline whether or not it had the cursor, which the Min and Max dialog made worst: two accent underlines and one caret to tell them apart. The field with the cursor keeps the accent and the rest sit quieter.

* A Feature Gate Lab override that fails to save no longer leaves the switch showing the value that was refused. The toast said it could not save while the screen carried on claiming the new value; the controls go back to what is actually stored.

## 0.32.0 (2026-09-15)

* Every control Hushfeed draws over the video answers a press and shows where the focus is. The four feed controls, the two actions on the daily hold, Undo, Clear all in the inbox and the sticker Save button used to paint the same thing pressed, focused or at rest, so a keyboard, a d-pad or switch access moved through them with nothing on screen saying so. Each one ripples under a finger now and carries a white ring while it holds focus.

* The settings screen's hand-built actions do the same. The Feature Gate Lab's view tabs, source tabs, Filter, its selection actions and the value picker, the creator editor's actions, the settings search box and the rows in the SIM preset and tab pickers were flat in every state. Tabbing through the Lab shows the focus moving now, a tap on a tab ripples, and the value picker has the frame that says it is a control. The preset and tab rows painted an opaque fill that covered the press highlight the list would have drawn; the fill sits under it now.

* A gate you have chosen in the Feature Gate Lab looks chosen. Selecting rows for an action changed nothing about them, so the count in the action bar was the only sign anything was picked. A chosen row now carries a tinted fill, an accent edge and a check mark at its start.

* The daily hold no longer reads itself out once a second. The whole panel was a live region and three of its lines were rewritten every tick, so TalkBack repeated the hold over the audio for as long as it stood. The panel is announced once when it appears, only the time remaining is live, and nothing is rewritten unless it changed.

* The daily budget label stays on the feed. It asked the loose question the block button asks, which counts a video opened from a profile grid or a search result and says nothing about the comment sheet, so opening the comments took the feed controls away and left the label sitting on top of the sheet. It now shows only when the Home tab is on screen and selected and nothing is covering it.

* The Region rows stop asking for a restart twice. Both summaries carried their own "and a restart" and then the shared restart sentence was added under it. Each says it once.

* The Region summaries name the switches they depend on by the titles those switches actually carry in German, Spanish, Indonesian and Brazilian Portuguese. A German phone told you to turn on "SIM-Details überschreiben" while the row above it was titled "SIM-Angaben überschreiben", and the other three tables had the same drift. A check now holds every summary that names another row to that row's title in every language.

* The Diagnostic tools description in Morphe Manager says Hushfeed rather than the project it was forked from, and says where the switches it adds live: under Diagnostics in Hushfeed settings.

* The README's settings captures were re-taken after the recent changes, and the page that shows a settings failure is captured in English rather than in the language of whichever phone took it last.

* The hidden creators editor keeps what you typed and says what is wrong under the box. Type a handle and press Save without pressing Add and the dialog used to close, then a message appeared over whatever was behind it, and the handle was gone. Adding an empty, duplicate or broken entry did the same thing over the open dialog. The reason now sits under the field it is about, the dialog stays open, and nothing is written until it can be.

* One sentence asks you to restart TikTok, not nine. The same screen used to say it four or five different ways, sometimes twice in view of each other, and each wording was its own thing to translate. Every row summary and every single-change message now says "Restart TikTok to apply this." Restoring, resetting or undoing settings, where several moved at once, says "Restart TikTok to apply all changes." Nothing else.

* The section rows on the settings screen say how many settings you have changed, not how many are on. The number was always a count of settings away from their default, and two of them arrive switched on, so turning one of those off read as having turned something on.

* Four patch descriptions in Morphe Manager no longer end in a stray space left behind when a sentence was cut, and two no longer carry a credit line in the text somebody reads while deciding whether to select the patch. The credit is in the README, where it has a link to follow.

* The release gate is now itself tested. The check that holds the README and the published index to the generated patch catalog runs on every push and before every release, and had never once been shown to refuse anything. It is now driven against a copy of the checkout with one fact moved at a time: an index naming another version, a patch count that is not the catalog's in either the index or the README, a README pointing at a Manager too old for the patcher, and a download link that answers nothing.

* Hook status speaks for the sensitive-warning switch. Turning those overlays off means writing one flag on a model TikTok owns, and if that model is renamed the switch still reads on while every warning stays exactly where it was, which looks the same as a video that was never flagged. The row now names the field and the model it looked in.

* The README and the Region page answer the three questions people keep asking. Downloads has always ignored the flag TikTok sets when a creator turns downloading off, and nothing said so. Region settings now says plainly what spoofing cannot change, which is your IP address, your account's history and the language you read in. And a patch run that sits at 24 or 25 percent forever is a memory problem with a known way through it, which the install steps now give.

* The daily budget rows and the feed tab picker are translated. Six Playback descriptions, the unit words beside their numbers and every tab name in both pickers had no entry in any table, so a German, Spanish, Indonesian or Brazilian phone showed them in English under a translated title. Both translation checks were blind to it, because a row's text is joined with its range and current value before anything sees it. The check now reads the wording from the row itself, where nothing has been joined to it yet. The feed tab names also read the same way as their siblings now: Nearby, Friends, LIVE, STEM and Drama and Series.

* The two country lists in the feed filter check what you type, and "UK" now works. Only from these countries took anything at all, and an entry that is not a country code matched no video, so one typo hid the whole feed with nothing on screen saying why. The row names the entry it cannot use before it saves, "UK" is read as the GB the videos carry, and a list saved before this check existed can no longer empty the feed either.

* Copying a sound link now cleans it the way copying a video link already did. TikTok's share URL for a sound carries the parameters that say who sent it, and the long press put it on the clipboard exactly as written, so the identifiers travelled with every shared sound. The link is stripped now and takes the custom share domain like the video link does. Both copied links are also marked sensitive, which they were not: they were the only clips the bundle wrote without that flag.

* The Not interested button no longer goes dead for the rest of the session. It marks itself busy before handing the request to the shared worker pool, and when that pool is full the request is refused and never runs, so nothing ever cleared the busy mark. The reader got one "Sending feedback" that never resolved and then a button that did nothing at all. A refused request now says so and leaves the button ready for the next tap.

* The App behavior page can be opened again on a bundle that carries only the launcher shortcuts patch. The row into that page asked a list of patches kept separately from the page's own, and the two had drifted by one: the shortcut switch was built, and nothing on the home screen led to it, so the settings search was the only way in. The page answers for itself now, and the test that sweeps one patch at a time covers it.

* The text, range and tab rows say when they need a restart, the way the switches already did. Nine of them carry restart-gated settings, the country lists, the five count filters and the two tab pickers, and said nothing about it, so a change that showed no effect looked like a hook that had stopped working. A test now walks every settings page and holds every row whose setting needs a restart to the sentence.

* The About row names the TikTok build as well as the Hushfeed version, and the settings search finds it. The row sat outside the search index with no key, so "version" and "hushfeed" both came back empty on the phones that showed it. Searching either now lands on the row, and the second question on every bug report, which TikTok the bundle was applied to, is answered on the same line.

* Diagnostics has a row for the failure messages. The switch behind them was on from the first release and had no row anywhere, so the only way to stop a message was to turn diagnostic logging off with it. Show failures on screen now sits under the logging switch and decides on its own whether a failure is put in front of you while logging is on. Off keeps failures in the report alone.

* The exported diagnostic report no longer names the creators you block, hide or follow. The block, hide and Not interested paths wrote the creator's display name into the log beside the toast that showed it, and every toast is written to the report as it is shown, so a report attached to a public bug had the names in it. Log lines now carry a pseudonym instead, the same one the follow report uses for the same account, and the name in a toast is marked so the export leaves it out in every language. Handles standing on their own are dropped too. Follow diagnostics phases are debug-level lines now; the refusal itself still reports at once.

* Double tap set to open comments now opens them. It pressed the comment button's click listener, which on the current app is a placeholder that does nothing while the real press is handled as a touch, so the double tap returned true and nothing opened. The press now goes through the comment assem's own icon-press method, the one TikTok's keyboard shortcut uses, with all of TikTok's own checks in front of it. Confirmed on the S22 on 46.2.3: do nothing leaves the like and the video alone, comments opens the sheet with the like untouched, and the default still likes.


## 0.31.0 (2026-09-14)

* The exported diagnostic report now says which feed filter route handled each list, whether or not diagnostic logging is on. Several routes can put a video on a profile page or in the feed, and an advert in a screenshot cannot say which one delivered it, so every ad report so far has needed a second round of questions. Each route now reports the lists it was handed, the videos in them and what it took out. A route that has never run has no line at all, which is the answer that was hardest to get before.


* Double-tap controls now reach the feed the app actually uses. Setting it to "do nothing" still liked the video, because the guard sat on the older feed panel and the current one never goes through it: its gesture listener holds the like component and calls it directly. Every route from a double tap to a like was traced on the app itself, and there are four of them, including the landscape player and the Friends tab. All four are guarded now. Two more classes carry the same method and neither can like anything, so they are left alone.


* Playback quality stops complaining about a model it cannot read. On some builds and accounts TikTok's video model getter hands back an empty string, and the quality picker parsed it anyway, failed, and put "Could not read the playback quality model" on screen once per video. It now hands back whatever it was given and leaves the quality to the app, which is what it did all along, without the message. The Hook status row names the getter that supplied the unusable model, so a diagnostic report says which one it was instead of the feed saying so repeatedly.


* Error messages from inside Hushfeed no longer appear over the feed unless diagnostic logging is on. They carry the name of the class that failed and the exception's own English text, past every translation the bundle ships, and on a path the feed runs one of them fired once per video. They still reach the log, the diagnostic buffer and the exported report, which is what a bug report needs, and the same message now shows at most once per run rather than queueing behind itself.

* A switch that needs TikTok restarted says so. Fifty settings do nothing until the app starts again and only thirty-four of them mentioned it, so sixteen switches moved and nothing happened, with nothing on screen explaining the gap. The sentence comes from the setting itself now, so it is on all fifty and cannot be forgotten on the next one added.

* The four controls Hushfeed draws on the feed, the budget label and the hold's release control now share one backdrop and one corner radius. They were built five separate times from the same two colors and then rounded three different ways, so a column of controls sitting on the same video read as three unrelated add-ons.

* The About row shows which version of Hushfeed is installed. It was only in the exported diagnostic report before, so there was no way to read it off the screen, and searching the settings for "version" found nothing.

* The Feature Gate Lab says which of the search and the filter emptied the list, and offers to clear the search when that is what did it. It used to blame the search either way, because the message was written once when the catalogue loaded. The gate details page's "no longer available" state now offers a way back to the Lab it tells you to refresh.

* When settings will not open, Try again comes first and is drawn in the accent. It was listed under Go back in the same weight and color, so the first thing offered was the way out.

* Save media on a sticker, the tap that starts the feed after a hold, and both actions on the settings recovery page are offered to a screen reader as buttons rather than as text. Save media also has a 48dp floor now, which it did not when the TikTok row it copies its size from was shorter.

* Four places spaced a row from the physical left edge instead of the start edge, so in a right-to-left layout the gap between a menu icon and its label, the gap before the chevron, the indent under a share action and the inset in the Lab's search row all stayed on the wrong side.

* Comment search takes its eight colors from the same palette as the rest of the settings screen instead of its own copies of them. It still works out the comment sheet's theme itself, because that is not always the system's.


* Ghost mode no longer closes the app when a story is opened. The old guard returned early from TikTok's story and profile reporters with a made-up null where the caller expected the lazy request it was about to enqueue or subscribe to, and the caller fell over on it. The same null was handed to the profile page's view model halfway through building the page. The guard now sits at each place a reporter is called and steps over the whole send, so the reporter is never called and the caller carries on with what it was doing. A reporter whose caller only checks for a suspended coroutine completes with nothing instead, and the typing indicator, which returns nothing, keeps its early return. A patch test walks the four caller shapes shared by 46.2.3, 46.7.3 and 46.8.3 with the guard answering both ways, and the patch refuses to apply to any chain it cannot follow to its send rather than guessing.

* New Playback setting, "Show what is left of the budget", off by default. A small label at the top of the feed shows the minutes or videos left of today's budget, whichever is closer to running out. Both budgets were previously only visible at their own edges: a reminder partway through, a notice when the day was spent, a hold if one was set. Between those you were guessing. The label appears only on the feed, goes away under the hold, changes at most once a minute, and is never read out on its own. A screen reader finds it in traversal and hears the value then.

* Reset position on a feed control now moves only that control. It used to re-place every control that had never been moved by hand, which meant resetting one of them could jump two others across the screen if the block button had been dragged since.

* Inbox Clear all says it is working on Android 10 and older too. The control's description is what a screen reader is given instead of its label, so changing the label alone left the whole eighteen second run announced as an ordinary disabled button. A heading TikTok rebuilds mid-run now also comes up saying the run is still going, instead of looking pressable while silently refusing.

* The four feed controls can now be moved with a screen reader. Each one offers Move up, Move down, Move left, Move right and Reset position, in the phone's language. A move shifts that control by its own width and stops at the screen edge, reset puts it back where it started, and neither touches the other three. The pointer long-press drag is unchanged, and an accessibility long-click is still refused because it has no release to end the gesture with.

* Inbox Clear all now shows what it is doing. Clearing up to 60 suggestions takes about eighteen seconds, and the control used to sit there looking pressable the whole time. It now reads as a button to screen readers, fades and refuses presses while a run is going, says "Clearing" as its state, and comes back on every ending: finished, nothing to clear, the 60 cap, the Inbox closing, or a dismissal that failed. A failure says how many accounts it got through before it stopped, and the run can be started again straight away. Every outcome is announced as well as shown.

* Robolectric tests now use Bouncy Castle 1.85.2 instead of 1.81. Every TikTok extension test inspects the resolved dependency graph first, and Gradle's reviewed checksums no longer accept the vulnerable jar.

* Feature Gate Recorder copies now use Android's sensitive clipboard flag while keeping the existing success, large-report and failure messages. Empty reports remain safe to copy, and reports above 60,000 characters still require Save JSON without replacing the clipboard.

* Settings pages now replace partial content with translated Back and Retry actions when loading fails. A failed preference update restores the typed saved value, dependent controls and default-value storage on the main thread, then reports one translated result. Nested recovery callbacks keep the outer update locked, and restart-required settings report success only after the complete update succeeds.

* Comment search now shows a translated result count below the field and explains when nothing matches. The polite status follows scrolling, recycled rows and cached sheet reattachment without moving focus. Turning search back on restores row tracking even when TikTok does not bind the rows again.

* Feature Gate Lab messages now stay in the selected language, including errors and current state text. Block and unblock results do too. Gate keys, raw type spelling, values and creator identifiers remain unchanged.

* Text and number settings, download destination dialogs and Feature Gate object fields now give screen readers one named editor. The spoken node keeps the current value, required keyboard and disabled state without repeating the visible label.

* Caption and creator filters now parse unchanged lists once per stored value instead of once per video. Feed-rule lists accept up to 10,000 entries or 256 KB, and oversized dialog edits or imports stay out of storage with a translated explanation.

* Downloads with a missing or malformed size header now recheck free space while streaming. They stop and remove partial media before reaching the 32 MB floor, with another 8 MB kept for publication.

* Rapid Feature Gate detail edits now save in tap order. Only the newest result can update the screen, and one Undo returns the whole burst to the value that was present before the first tap.

* Settings backup, restore, reset and Feature Gate Lab changes no longer stay locked when the shared worker queue is full. Rejected Lab switches return to their stored position so the next tap retries the change, and a translated message asks you to try again.

* The Add Hushfeed links now use the encoded repository slug. The install steps also include a manual Manager path for browsers that refuse to open other apps. The browser button, source download and Manager 1.29.0 and 1.30.0 flows were exercised on the S22.

* Remove feed ads now catches videos carrying TikTok's Creator earns commission disclosure. It reads the structured anchor label rather than matching visible English text, and malformed or unrelated anchor data stays in the feed.

* Comment tools can now remove TikTok's polls from the comment sheet before they are drawn. A missing or changed poll model leaves comments working and identifies the broken contract in Hook status.

* The block, local-hide, sound and Not interested controls now disappear when TikTok's comment sheet covers the feed. The local-hide and sound buttons have their own switches, and each visible control can be moved and remembered on its own. Enabling local hide after the controls attach keeps it in its own slot instead of covering the sound button.


* Eight optional TikTok optimizer patches from Kveld are now built into Hushfeed. The four resource patches validate complete path and SHA-256 profiles before emptying language, creator, LIVE, C2PA or card-scanner assets. The startup, network, Fresco and update patches use exact reviewed method contracts, stay off by default and describe their feature tradeoffs. Disable telemetry also covers TikTok's two Npth startup tasks behind its existing switch. The pre-push preparation check can keep the published 71-patch index intact while this unreleased catalog grows at the same version. Strict publication checks still reject the mismatch.

* Media downloads now validate the original address and every redirect. They allow only public HTTPS destinations with no embedded credentials, stop redirect loops after five hops, recheck DNS before connecting, and remove partial files when a destination or body is refused.

* Right-column counts now follow both parts of TikTok's feed layout: the outer count row and the numeric text inside it. Layout variants that replace the row can no longer leave like, comment, favorite or share counts visible, and turning the switch off restores only the views Hushfeed hid.

* Hide inbox stories now responds without restarting TikTok. It hides only the story tray, restores recycled rows when they change purpose, and shares one live Inbox observer with Hide inbox items when both patches are selected.

* Remove feed ads now recognizes TikTok's raw auction metadata even when both native Boolean ad flags are false. The shared check covers response, cache, Following, Friends, profile, search and late-insertion feeds while leaving ordinary paid-partnership posts alone.

* Share sheet confirmation now handles every click path, including accessibility actions and keyboard input. It follows the recipient's account or conversation ID. Reusing a row or letting the timer expire resets the check, and TikTok's original row highlight comes back. If the confirmation setup or setting read fails, the tap is stopped and an older confirmation is forgotten. Turning confirmation off restores normal sending even if an old row highlight cannot be cleared.

* Feature Gate Lab now checks its 1,024-rule limit before writing a change or importing a file. Reset all Lab data can recover an older oversized store without clearing other Hushfeed settings.

* Settings Undo now recognizes an interrupted current or legacy undo write when only its recovery file remains. It always prefers the current Hushfeed copy, and the next verified snapshot moves storage off the old Metra filename.

* Injected-register verification now requires identical Android verifier message counts from clean and patched APKs. Its static check also rejects removed host methods and DEX files unless an exact reviewed removal is listed beside the verifier.

* Device verification now removes each uploaded APK and its generated ART files after every result, including failed uploads and disconnected runs. Cleanup errors are reported without replacing the verification failure that caused them.

* Session budget recovery now validates the whole persisted record before making any of it live. Damaged fields and invalid shapes reset to clean current-day values and are rewritten, while historical five-field records and the current nine-field format keep their exact meaning.

## 0.30.2 (2026-09-13)

* GIF conversion now has separate deadline regressions for its color-precision, palette and frame-encoding passes. Each check has to stop before the second frame, so losing one cannot hide behind an earlier pass.

* Logger keeps a failed message builder contained even when the thrown object also breaks its own `toString()` or `getMessage()`. The emergency line falls back to fixed text and the original hook can keep running.

* Comment tools now resolves every native anchor, reply model, field and register before it changes the APK. If a later TikTok build makes the reply row ambiguous, patching fails without leaving the settings flag or dislike redirect behind.

* The obfuscated identity audit now records Java binary class names such as `X.0GSy`, as well as smali descriptors. Exact token boundaries keep longer names and ordinary Java text from being counted, and a regression proves a new `Class.forName` literal stops the build.

* Patch result checks now accept dependency rows declared by a selected patch while still rejecting a missing root or any duplicate or unrelated row. Device patching and fixture checks use the rule, as does heap measurement.

* Cached model reflection now finds public methods inherited from interfaces. Follow diagnostics uses the required lookup for a request path, so a future TikTok rename appears in Hook status instead of making every path look unknown.

* Video fitting now hands a changed center gravity back to Android so the view is laid out again. The story path forgets its fitted result after both offset reads, and its tests now prove the feed and story paths independently read an uncopyable result.

* Lazy AB gate discovery now follows one integer register move back to the factory number, including a second four-instruction lookback. A merged-lambda entry point must switch on its own integer `$t` field, so an unrelated field and switch cannot make a decoy class look like the requested gate.

* Passing a Java directory without `bin/java.exe` or `bin/java` now stops with that directory in the error. It can no longer ignore the explicit choice and run a different Java from `PATH`. Java discovery also works in Windows PowerShell, where the normal `java -version` banner arrives on the error stream.

* Device replacement now checks whether TikTok is installed before trying to remove it, so a clean phone proceeds to installation. Device patching, fixture verification, heap checks and release validation all read the target package and version from the generated patch catalog.

* CAPTCHA patch validation now follows request and callback methods inherited from superclasses or interfaces. The risk-control hook also refuses a static `execute` method because its injected parameter registers require an instance receiver.

* Settings journal recovery now has direct regression coverage for a valid record that storage refuses to apply. The test requires the failed recovery notice, retains the record for diagnostics and confirms that the next settings change can start.

* Each unreadable settings journal now gets its own recovery notice. Its fallback identity includes the actual file path, length and modification time, so another damaged record at the same path is not hidden for the rest of that process.

* A settings journal that was recovered successfully keeps that result when only deletion of the journal fails. The record is set aside for diagnostics, while the notice still reports recovered or already completed settings.

* The release check now ties the README's minimum Morphe Manager version to a `manager-floor` value beside the patcher pin. Device-only signing helpers read their password from `HUSHFEED_SIDELOAD_KEYSTORE_PASSWORD`, retain `sideload` as the documented local test-key fallback, and keep the value out of child process command lines.

* The README patch table now uses the same descriptions Morphe Manager reads from the shipped catalog. A regression checks every name and description, so changing either side cannot leave the project page describing a different feature.

* Patch failures now name the selector or method contract that changed instead of ending in a collection or null crash. The offline option hook requires the exact six-register enum constructor it reads. Story downloads require both bind methods, and the settings row reserves low registers for its plain invokes. Region spoof keeps the getter's documented first-field rule. Screen-capture patching no longer writes a count to standard output.

* The release gate now checks the published bundle against the version tag during the final index push. The index is committed after the artifact exists, so comparing the bundle to that later commit rejected a correct release.

## 0.30.1 (2026-09-12)

* TikTok's own Photo Mode save now gives every image its own `{index}` number instead of naming the whole slideshow as image 1. This also prevents later photos replacing the first one on Android 6 through 9. Animated WebP to GIF conversion now budgets the canvas and decoded frame alongside the stored frame pixels, which cuts the worst pixel allocation from about 100 MiB to 32 MiB before encoder overhead. Turning off Keep captions in clear display removes its overlay and layout observer immediately. Media jobs keep their two minute deadline without carrying a cancel path that no shipped control could call.

* Creator identity now uses the same canonical value for equality and hash collections. Fire-and-forget background and main-thread callbacks report every failure instead of letting an Error escape into TikTok. Android API checks now cover the full shared and settings source rather than being disabled for two whole files, the TikTok stub matches the app's API 23 floor and has its own namespace, and artifact verification rejects any DEX payload with no classes.

* Silent reflection failures now reach Hook status for CAPTCHA account checks, external browser handoff, sticker source adapters and story saves. Each feature keeps its existing fallback, while a renamed required class, method or field identifies the affected family and member in Diagnostics.

* The dark and light Included diagnostics screenshots now come from the shipped preference. Both show its eight real choices and Apply and Cancel actions. The capture test rejects a missing choice or the return of the invented Done button.

* The Feature Gate Lab filter now uses the same themed radio rows as the other settings pickers. Rows stay fully readable while selecting several gates. Exporting before values have loaded says why it cannot start, and a selected import keeps going if the Lab closes while its file is being read. The Lab itself now appears when its title or description is entered in Settings search.

* Numeric dialogs let long descriptions scroll without squeezing out the field, and an empty number stays open with a clear explanation. The sticker save button follows the current text colors when TikTok gives it no text button to copy. Clearing diagnostic data can be undone with the next tap, including buffered events, saved crashes and hook findings.

* Feature Gate Lab saves now confirm when they finish, and disabled values have a visible muted state. Its custom actions are announced as buttons, source tabs report the selected tab from the focused control, and filtered lists in the Lab and settings announce their result count. Creator removal says which entry was removed and moves focus to the next action. Number fields now carry a hint, and custom dialog titles are marked as headings for screen readers.

* Hooks that change a value as TikTok returns it now keep the return's branch label on the hook. A direct jump to that return can no longer skip Hushfeed's filter, setting override, diagnostic callback or UI attachment. The same rule now covers every current return hook, including ones added after the original audit.

* Settings no longer unpacks the APK just to find the Hushfeed row's gear icon. The patch reads the icon from TikTok's own Ad Browser settings action, following the method that the stable icon renderer calls. Ordinary patch jobs can now stay in bytecode-only mode. The full resource rewrite still runs when the optional AMOLED theme is selected.

* Feed filtering follows TikTok's rebuilt cache delivery on 46.7.3 and 46.8.3. Cached videos now pass through the same filters before either new callback wrapper sees them, including the local seen-video history. The older 46.2.3 chain stays supported. A rejected reach-bottom cache result is also marked unsuccessful, so TikTok asks the next source for a video instead of treating the rejected one as delivered. The friend recommendation-card selector now tolerates TikTok swapping its String and List parameters, and the Tako button selector follows stable view behavior instead of its changing method name.

* Hide quick comment reactions works on TikTok 46.8.3 after TikTok removed the experiment the patch used to change. The reaction row still exists, but an Aweme check now decides whether to show it. The patch follows the row itself and applies the setting to every native visibility change, which also keeps 46.2.3 and 46.7.3 working without their old experiment name.

* The project page now opens with a 1600 by 900 hero built around the approved H and current native settings screens. The artwork carries no release number, so a routine version bump cannot make it stale.

* The README puts the Morphe source link, latest bundle, settings tour and patch list near the top. Its feature overview is shorter, the exact supported TikTok build is harder to miss, and the release link points at the current bundle.

* The previous header, both finished hero layouts, README-width previews, fresh offscreen screenshots and rejected mark studies now live in the marketing concept archive. A regression test keeps the selected hero first, prevents a second copy, checks its dimensions and locks the approved H to the supplied file.

## 0.30.0 (2026-09-10)

* Fit the video to the screen now works on the For You feed. The feed cell never sized its video through the method the patch hooked; it hands the size to a helper of its own, and that helper is what the patch reaches now, on every build seen. A fitted video is handed back as a copy of TikTok's own answer, so the size the feed applies and the size it checks against are the same one. The story cell's own path is still covered, and the copy it keeps is looked up by the object rather than by its numbers, which a size change had moved out from under.

* Fit the video to the screen no longer writes a size of nothing into a story when it can't read the video's size. On every path where it has nothing to fit, it leaves TikTok's own numbers alone, the way it did before the feed rework. The patch also refuses a build where either feed helper it hooks has a twin, instead of hooking whichever one it met first, and checks the size getters and the copy method the feed path relies on before it writes anything. With the switch off, the feed skips the size lookups altogether.

* Anchors that find a setting behind a lazily read value no longer skip a class that carries two methods of the shape they were looking for. Before, such a class was passed over as if it read some other key, and the failure said no class read the key at all. The walk through R8's merged lambda groups also follows entry points that take an argument, reads every no-argument method of a lambda class rather than the first, takes the constant from the register the factory call actually reads, and answers only from the switch that opens the dispatcher.

* A settings journal that can't be read or applied is set aside instead of being left in place. Left there, every later settings change, Undo and Restore included, was refused with a notice telling you to use Undo or Restore. The notice now says what happened. The lock that guards the journal is also released through a finally block, so an out-of-memory error during recovery can't leave every later change waiting forever.

* Twelve toasts and one dialog in the Feature Gate Lab and the diagnostics export reached the reader in English on a translated phone, several of them branded for the shared library rather than for Hushfeed, and two carried an exception's own text. All of them go through the translation table now. The Undo entry says there is nothing to undo instead of showing the path of a file that doesn't exist, and an import that accepted nothing no longer offers an Undo that would revert the previous Lab change.

* The creator list dialog saves a handle that was typed into the box and never added with the button. It was dropped without a word.

* The free-space check before a save no longer stands down when the destination folder doesn't exist yet, which is exactly the first save into it. A crash report cut at the size ceiling now says so, at the top as well as where it stops, and is cut on a character boundary rather than in the middle of one. Converting a sticker to GIF checks the job's deadline once per frame, while it decodes and again while it encodes, the way the MP4 path does, so a small canvas with tens of thousands of frames can't hold a media worker for good. A muxer that fails mid-copy no longer replaces the real error with its own complaint on release.

* A log message that fails while being built, or a log line that can't be written, no longer throws out of the hook that was logging, into TikTok. The follow diagnostics path looks up the request's path through the cached reflection helper rather than an uncached lookup on every network request.

* Patches refuse more of the builds they can't patch, by name, rather than applying and doing nothing or shipping an instruction a phone's verifier rejects: every injection that writes a scratch register at the start of a method checks that the method has a local to write; every call that names a register read off a return uses the range form; the quick reactions gate reads its return register off each return instead of assuming v0; follow diagnostics counts its hooks and fails when a rename leaves one unhooked; the photo download anchor no longer matches the lookup method beside it; the risk-control CAPTCHA patch checks the request and callback shapes it calls; and a missing instruction is reported with the method it was missing from.

* Follow diagnostics checks all three of its hooks before writing any of them. A build that renamed one used to fail the patch with the other two already written into the app.

* Resume videos after scrolling no longer writes three of TikTok's internal class names into the code it adds. Those names only mean the right thing on 46.2.3. On a newer TikTok they belong to other classes, so the patch applied without complaint there and the first video played to the end with the switch on would have stopped the app. It now reads where TikTok keeps its playback positions off TikTok's own code, and refuses a build where that doesn't add up.

* Comment tools reads the reply row's model off TikTok's own code too, instead of naming it. The search box above the comments uses that model to keep a collapsed reply row collapsed, and three of the four names it needs are different on a newer TikTok.

* The thumbs down that Comment tools turns into a block button is found by what it does now. TikTok installs the like and the dislike touch handlers back to back, and the patch used to take the second of the two by the name of the view it sat on. It now follows each handler to the code that runs on a touch and takes the one that asks whether the comment is disliked, so the block gesture can't end up on the like button if the two ever swap places. The same change lets Comment tools apply to TikTok 46.7.3 and 46.8.3, where it used to fail.

* Custom offline videos limit finds TikTok's lists of offline choices through the offline page that reads them, instead of by the name the class had in 46.2.3. Newer builds keep four lists instead of two, and the custom limit is added to every one of them, so the patch applies to TikTok 46.7.3 and 46.8.3 as well.

* The Hook status row in Hushfeed's debug settings says so when a build has renamed the bottom navigation tabs. The block button relies on the Home tab to tell the feed from other screens, and on a build without it the button quietly stopped hiding itself off the feed.

* Search features, Allow Duet and Stitch and Open links in external browser no longer refuse a build just because the value they read sits in a high register. They use the instruction forms that reach it.

* A crash report that was being written when the app died is read back from the last whole copy. It used to look as though no crash had been saved.

* The included diagnostics picker won't apply with every kind cleared. It used to save that as all events and then say it included all of them.

* The Feature Gate Lab says why it turned down a loaded-values file: it's from another TikTok version, it isn't that kind of file, it has no values in it, or it has more than the Lab takes at once. It used to call every one of them invalid or too large.

* Automatic video advance and Comment sort controls override the setting they read by following the setting's key to the lookup it is handed to. They used to take the first answer after the key, which on a build with another call in between would have been some other value.

* A settings backup carrying more Feature Gate Lab rules than the Lab keeps is refused, and the refusal says so. A hand-edited one used to restore, and after that every backup, every Reset and every Lab change failed because the next copy was too large to write.

* The pre-push check runs the patch module's tests as well as the runtime ones, and treats the version catalog, the settings script, the dependency verification file and the Gradle wrapper as release facts. A push touching only one of those ran no gate at all. A README edit on a clean checkout is no longer blocked by a release check that wanted a built bundle it had just said it didn't need.

* The device scripts read as success when adb refused an install, chose the clean APK by file size, could carry one dex2oat exit code over to the next run, left gigabytes of unpacked APK behind, and wrote an empty screenshot when the capture failed. Each of those is fixed. The verification probe compiles again and holds its broadcasts to the DUMP permission, which the shell has and an ordinary app can only be given over adb.

* The register check keeps its report when it fails. Run without a report path, it named the report in its FAIL line and then deleted the folder the report was in.

* The README's Patches heading renders on GitHub again; a stray line break tag had swallowed it into body text. The bug report template asks for the phone and its Android version, and its example names a Manager release that can load this bundle. The split-view width setting says dp, which is what it compares against, instead of pixels. The Feature Gate Lab no longer says "No gates match" under "Loading" before it has looked, every switch on its detail page has a name for a screen reader, its page title follows the font scale like every other page, the share checklist's boxes take the theme, and the greyed Undo row says why it's greyed.

## 0.29.0 (2026-09-10)

* A new switch empties the menu that opens when you press and hold TikTok's icon on the home screen. Those entries are not declared anywhere in the app, TikTok builds them while it runs, and it only rewrites them when it notices a difference. So the switch takes away what is already published and answers the handover that would publish more. Turning it off asks TikTok to build them again. Tapping the icon still opens the app, and a shortcut you pinned to a home screen yourself is left alone.

* The v0.28.0 download has been replaced. The bundle pins a build stamp to the commit it was built from, so that anyone can rebuild it and check the published checksum against their own, and that file was built a couple of commits before the release it shipped as. Its patches were the right ones, entry for entry; only the stamp was wrong. The release check now reads the stamp back and refuses a bundle that was not built from the commit being released.

* Everything the patches inject is now checked against the register count of the method it lands in, and against the Android runtime's own verifier on a phone. A patch that writes into a register a method never declared assembles cleanly, applies cleanly, and fails only on a device; nothing here does that.

## 0.28.0 (2026-09-10)

* Automatic video advance now works from a cold start. Two things stood in the way. TikTok builds its auto scroll component only once somebody opens the video panel and asks for it by hand, so with the setting already on, a fresh launch had nothing to work with. And the check for whether the feed was on screen asked TikTok's auto scroll indicator about itself, which TikTok keeps hidden until scrolling is already running, so the answer was always no. With the setting on, the component is now built alongside the ones TikTok always builds, and the feed it sits in is what answers for being on screen. With the setting off, TikTok's own choice is left alone. Automatic advance also looks again when a video ends rather than staying down for the rest of the session, so changing the session limit or coming back to the feed picks it up again without restarting TikTok.

* Changing the auto-advance session limit now starts a fresh count on the same feed. The new session can count its current video and show its own stop notice. Saving the same limit, changing another setting or returning from settings keeps the existing count.

* A final playback report can arrive before TikTok applies a daily hold's queued pause. That report now keeps the matching resume ready for audio focus to return. Playback after an observed pause still releases that ownership.

* A daily hold now keeps its paused video ready when another app owns audio focus at expiry. A later native focus grant can resume that same video once, provided its feed is still visible. A reported independent resume gives up that ownership, so a later pause won't be undone.

* Profile-picture saves now try TikTok's largest image before the medium and smaller variants. The previous priority picked a 300-pixel image even when the current profile supplied a 1080-pixel original. Current-profile ownership and secure fallback URLs are preserved.

* Long-press actions now run from the current feed's coordinate callback as well as its older gesture listener. The patch keeps TikTok's own timer and menu coordinates, and hands the gesture back when the chosen action doesn't handle it.

* Edge seeking now converts the requested position into the percentage TikTok's player expects. Its position, video identity and duration checks still use milliseconds. The player fixtures were corrected to use the native conversion, which exposed this mismatch.

* Turning comment search off removes its box from an already open comment sheet and restores the rows it hid. Turning it back on works with those same loaded comments, while rows TikTok collapsed keep their native state.

* Selected patches now activate on app launch, before their first runtime hook. Opening Hushfeed settings is no longer needed to turn them on, including search controls used before that page opens.

* Settings changes now survive an older settings page remaining open. Each page reads the saved choice before refreshing its controls, so an old switch or text field can't undo a newer choice.

* Custom long-press actions now take precedence over TikTok's 2x edge hold, including seeking by the chosen distance. The default keeps TikTok's behavior.

* Hiding search suggestions now also covers cached recommendations and search-page returns. Your search history stays available.

* The comment-sort description now explains that media and creator filters depend on the post's native availability flags. Device checks confirmed those optional categories and preserved the stock menu after disabling the switch.

* Profile-picture saving now handles the current profile header and resolves the picture from the pressed component. Story long presses reach the native child that consumes the gesture and save its current story, even when the outer view holds a collection. Turning either option off keeps the native gesture.

* Comment search now hides reply controls with their nonmatching parent comments. Clearing search restores the rows. Turning search off also releases its saved heights and visibility during native rebinding, with either comment-blocking setting, while preserving rows TikTok hid itself.

* Follow readback now recognizes the modern profile response and keeps each relationship state with its own account identity. A reported zero remains zero; conflicting states stay unclear. Diagnostics can report a mismatch after an accepted follow without guessing its cause.

* Daily-budget holds pause the current native player and resume only the player they held, while its feed is visible and audio focus permits playback. Holds survive settings returns and activity recreation. Their lower edge follows the actual tab row, including the system navigation gap, so Home and messages remain tappable.

* Enabling the block or Not interested button now attaches it to the current creator immediately. Disabling the last button removes the controls. A daily-budget hold keeps those controls hidden while the feed is covered.

* The phone helper rejects a log query without its required filter. That usage error no longer exits successfully after printing an error.

* The phone helper reports failed activity, log and frame-stat queries instead of hiding their exit status behind output filters. A screenshot no longer claims success when its foreground check fails.

* Changing font size or navigation mode keeps the Hushfeed settings page you were using. The restored page reuses its original container, and Back still returns through the pages you opened.

* Follow diagnostics no longer injects a String logger over integer arguments in TikTok's detailed follow caller. Both direct follow methods are covered inside their shared native API; the separate stream path keeps its own hook. Skipped and failed requests can't inherit an earlier diagnostic ID. Account pseudonyms now use keyed SHA-256 instead of a reversible short hash.

* New regressions press the installed search, Hook status and About controls, render the block symbol, and load all four generated gate catalogs. Caption checks drive real pre-draw callbacks through detach and activity replacement. Seen-history checks exercise initial loading, expired SQL records and the 200th progress-write cleanup.

* Comment blocking recovers when the worker queue is full instead of leaving the button busy. Turning it off restores the original dislike touch handler and the actual comment cell's appearance. A press already started as a block can't become a native dislike, and discarded cells can be collected. Rebinding a row during a press also keeps that release away from a cancelled native gesture.

* On Android 6 through 9, a profile-picture long press explains when storage permission prevents saving. Granting permission lets the same picture save. The message is translated into all four supported languages.

* Saving a video without sound preserves its rotation metadata before the muxer starts. Tests also check sample timestamps, flags and cancellation without deleting the input.

* Native GIPHY sticker URLs now obey the same HTTPS restriction as other sticker sources. Rebinding the save button to an invalid source can't leave the previous sticker attached.

* Story saving no longer keeps discarded story screens alive through its owner index. A live or rebound story still resolves its own media; a missing owner reports that the story is unavailable.

* An MP4 sticker that fails during graphics setup releases its encoder surface and graphics resources. Cleanup still runs if another release step fails, and the original error remains available.

* The two long-press copy choices now copy the current video or sound link. They were wired to double tap, whose settings never offered them. Default double tap is unchanged.

* Turning Inbox hide and expansion options off now preserves TikTok's own decisions. Suggested accounts and stories aren't forced into otherwise unavailable layouts, and a list TikTok already expanded stays expanded.

* Repeated taps on Inbox's Clear all control keep one paced run. Failed dismissals and a closing screen release the control so it can be used again.

* Enabling or saving a Feature Gate Lab override keeps populated arrays intact, including arrays loaded from TikTok. An empty default no longer replaces the edited value.

* Native graphics tests serialize font initialization across Android sandboxes. Graphics errors still fail the run, and every tested Android version remains enabled.

* Feed tests now run all five count ranges through real responses, with lower and upper boundaries, missing counts and disabled settings. The content fixtures also fail if the runtime catches an unstubbed getter, so an incomplete fixture can't quietly pass.

- Follow diagnostics now recognizes the same real follow write routes as the CAPTCHA gate, including nearby follows and follower removals. Diagnostic requests stay bounded after the session limit while refusal notices still work.

* The settings screen uses TikTok's theme before painting behind the system bars. Light TikTok on a dark phone no longer leaves the clock and battery on a black strip, and the reverse combination is covered too.

* The device helper starts correctly in Bash and refuses any serial except the S22 test phone. Failed device commands stop the step before it reports success.

* The oversized animated-sticker check now closes its test file on Windows. It still rejects the canvas before allocation and checks the rejection reason.

## 0.27.0 (2026-09-09)

* The settings screens are done being half English. Around eighty strings still went out in English whatever your phone was set to, nearly all of them in the Feature Gate Lab: the gate details page top to bottom, both override switches and the sentences under them, the overflow menu, the filter, the three buttons that reset or force a selection, and nineteen of its messages. The close button on the Lab's search box and the three buttons under the tab picker went with them. All of it is in the four tables now.

* Two of the Lab's lines count things, and they were built from a verb, a number and a couple of tails. No table row can hold half a sentence, and the plural rule of the language never got a say. Those are whole sentences now with the counts as placeholders, and so are the export count and the four-part import summary.

* The list under the diagnostics picker reads correctly in every language. It was "Includes downloads, errors events.", lower-cased from labels that every table capitalises, which suits English and nothing else. The list comes after a colon now: "Includes these events: Downloads, Errors".

* An undo of a settings backup says it's an undo. If the copy it put back held Feature Gate Lab rules for another TikTok build, the line on screen said your settings had been restored, and that's a different thing.

* Thirteen wrong translations. Brazilian Portuguese said to restart TikTok "para valer", which means for real rather than to take effect. German and Indonesian pointed at two settings by names those settings don't carry, and both called a video's caption a subtitle. Spanish and Portuguese write maximum as "máx.", and ten rows had lost the period that belongs to the word.

* The README named two patches by names they lost in 0.26.0, so looking either one up in Morphe Manager found nothing. The 0.26.0 notes also said seven patches were renamed when ten were, and gave the wrong number for how long a caption problem goes unreported.

* Four of the checks meant to catch all of this were letting it through themselves, and each now has its own failing case pinned in front of it, because a check that has only ever seen code it passes proves nothing. A patch-time helper that overrides the result of a call reads what an instruction writes more carefully too: five comparison opcodes were in no write set at all, and a conversion away from a long was read as though its answer took two registers.

## 0.26.0 (2026-09-09)

* The last English left on the settings screen is translated. The diagnostics picker with its eight kinds of event, the line you see after saving any of the fifty settings that ask for a restart, the message when diagnostic data is cleared, and every line of the hook status report. These live in code shared with other bundles that carry no translations at all, so they could not simply be wrapped: the shared classes ask this bundle for the words and fall back to English for anyone else. The report line was five pieces glued together, which no translation can hold, and is one sentence now.

* Thirteen more strings in the Feature Gate Lab are translated: the screen titles, the two warnings about what an override can and cannot do, the empty and loading states, and the reset action. They had been going out in English because the check that catches this looks at what is handed to a view, and these went through a helper first. The check follows the helper now.

* One word for one thing across the settings screens. The strip down the right of a video is the right column everywhere, not the action bar in one row and the action rail in the next. LIVE is written the way TikTok writes it. Eleven ways of saying "restart TikTok" are now one. A save that fails says so in one shape and tells you what to try. Favourite is spelled the American way, like the App behavior section it sits under. And nine patches were renamed to match the row they add, so a patch you applied by name can be found by that name in the settings: Skip content warnings, Fit the video to the screen, Keep the screen's refresh rate, Use non-personalized search, Show LIVE search, Show the progress bar, Show the progress bar thumbnail, Disable the long press quick share and Disable the long press repost. If you had any of those selected, select them again after updating.

* A pass over the words on the settings screens. Photo posts are called photo posts rather than "image video", which is TikTok's own internal name. The region rows talk about the region TikTok reports instead of "region getters". The auto-advance limit lost sixty words about prefetches and component lifecycles and says what it counts. A restore, a reset and an undo say which of the three just happened, rather than all three saying "Settings saved". Both "picker is not available" messages say what to do instead. The ghost mode summary got the word it was missing, and the file name help points at a row that exists. In the patch list, "Hide BdTuring CAPTCHA popups" is now "Hide the risk control CAPTCHA"; BdTuring is ByteDance's name for the service and it stays in the description.

* The dialogs that were still in English are not. The SIM country picker, the tab pickers and their help text, the Feature Gate Lab from its search box to its selection count, the gate editor with its value hints and status lines, and what a screen reader is told about all of them. Thirty-five more strings in German, Indonesian, Spanish and Brazilian Portuguese. Two of them had a German row all along and never asked for it, which is the sort of thing a check catches and reading does not, so there is a check now: text handed straight to a view in English fails the build.

* The daily budget costs less to keep. Working out which day it is used to read the device's timezone on every frame the player reported, and on Android that hands back a copy each time. It now listens for the timezone changing instead, which is the one moment the answer can be wrong, so the check on the hot path is two comparisons.

* One line of the settings screen was still in English on a translated phone: the summary under "Start today over" after you have tapped it. It is translated in all four languages now, and the check that catches this kind of gap taps the row itself rather than waiting for it to be found by accident.

* Hook status catches the other way the caption settings can go quiet. It already reported an id this build does not have. Now it also reports an id the build does have that has been handed to some other view, which is the likelier of the two, and which used to leave the caption size and background doing nothing while the row said everything was fine. It takes twenty renders of a container holding a caption view before that is called a problem, and forty for a container that holds neither, because TikTok's own render method returns early often and an empty container is not a broken build.

* The little "2 on" numbers on the settings menu mean what they say now. Each one counts the settings on the page behind it that you have moved off their default, worked out from the page itself rather than from a list kept beside it. That list had drifted: it named 17 of the 34 settings on the Feed filter page, so nine switches there changed nothing, and it counted switches that ship on as things you had turned on, which is why a fresh install showed "Comments and translation, 3 on" before you had touched anything. The numbers also move while you use the screen. Turn three filters on, press back, and the count is three; it used to be whatever it was when you opened settings.

* The settings screen speaks Spanish and Brazilian Portuguese. Both tables carry all 687 strings, so a phone set to either language gets the whole screen rather than a half translated one. Neither has been read by a native speaker yet. They were written here, against the English, and corrections are welcome: each language is one file under extensions/tiktok/src/main/l10n and a pull request against it needs nothing else.

* Two switches for a feed that plays when nobody is watching it. "Quieten the feed while comments are open" takes the sound the moment a comment sheet opens and hands it back when it closes. "Do not start the feed on returning" holds the feed after you come back to the app until you tap once, and leaves the tab bar alone so messages, a profile and search are still one tap away. Both are off by default. Neither presses a pause button, because there isn't one to press: they ask for the audio focus, the way the session hold does, which is how one app tells another to stop.

* You can see the hold coming now. "Fade the feed out before the hold" is off by default; switched on, the feed dims over the last three quarters of a minute of a time budget, most of it in the final half minute, and the last shade of the fade is the shade of the hold itself, so there is no jump. It needs a budget in minutes and a hold to lead into, since a budget counted in videos has no time left to follow and a fade towards nothing is just a dark feed, and it does nothing at all if you have turned system animations off. It takes no touches and a screen reader is told nothing until the hold speaks for itself.

* A value a settings dialog will not take no longer closes the dialog first. Type a creator pattern that will not compile, a smallest larger than the largest, or a folder Android will not write to, and the dialog stays where it is with what you typed still in it and the reason under the box. Change a budget row while today's budget is locked and the dialog stays put too, though that refusal is about the day rather than the field, so it is still said in a line at the bottom of the screen. Before this all three closed, threw the text away and put the reason in a toast over whatever was behind, so you reopened the row and typed it again.

* Small things across the settings screens. "Add" in the hidden creators dialog is flat like the Cancel and Save below it, instead of the one raised button in the app, and an empty list says so where the list is rather than under a blank band of it. "Reset to loaded" in the tab pickers ticks every row and leaves the dialog open, so pressing it to see what it does no longer saves and closes. Rows say milliseconds, pixels and points instead of ms, dp and sp. Clearing diagnostics says that it also resets the hook status. The SIM switch stops promising operator fields on a build that does not have them. And blocking a creator twice says the same thing from both places it can be said.

* At a large text size the settings page shows the page, not just its title. A page called "Kommentare und Ubersetzung" took five lines and most of the screen at 2x text, and the first row of the page was below the fold. The title still grows with your text size, just not without limit. The Feature Gate Lab's search box grows with it too, instead of clipping the letters in a fixed height.

* The hand-drawn arrows and chevrons turn round in a right-to-left language, and they are the width they were meant to be. Their containers already mirrored, so an Arabic or Hebrew reader had a back arrow pointing left at the right edge of the screen and chevrons pointing back into the text. Separately, every one of these strokes was given its width in raw pixels, so on a dense screen the settings menu tiles drew lines under a pixel wide next to very large type, and the shadow behind a caption was almost nothing on a 3x screen, which matters most when you pick the transparent caption background. The chevron on the Lab's value picker turns round too, which it could not do before: it hangs off the text rather than sitting in its own box, and a row built by a list adapter has no direction to take one from yet.

* Three small things on screens you actually touch. The four buttons on the feed are 48dp instead of 44, and "Not interested" is the same round shape over the same shade as the three it sits with, rather than a rounded rectangle over a darker one. Pressing a settings row at the corner of a card no longer paints outside the card. And every flat action in a dialog, the Saves and Cancels and Applies, is at least 48dp and is announced by a screen reader as a button rather than as a label.

* A quiet reminder partway through, if you want one. "Remind me every" takes a number of minutes and shows a short line at the bottom of the feed after that much watching, then again after the same again. It goes on its own, it takes no focus, and a screen reader hears it once. It counts feed time only, so messages and profiles do not bring one on, and nothing appears while the feed is on hold. Three wordings take turns, because one sentence stops being read. Zero, the default, means none.

* The Feature Gate Lab acts on a whole selection at once. Press and hold a gate to start one, tap to add and remove, then Enable, Disable or Reset the lot. It goes through as a single change, so one Undo puts all of it back rather than the last one you touched. Gates that do not take a true or false value are skipped and it says how many.

* Both daily budgets show how much of today has gone. Set one to 200 videos and the row reads "Today: 57 videos" under it, so the day's progress is visible where you set the budget instead of only in the one notice when it runs out. With a budget of zero there is nothing to measure against and the line is absent.

* The hold panel offers a way to your messages. It always said messages, profiles and search still work, and then covered the screen, so you had to already know the Inbox tab was underneath. There is an "Open messages" action under the countdown now. It opens the Inbox tab exactly as tapping it would, the hold stays up behind it, and it comes back when you return to the feed. If you have hidden Inbox in Feed navigation, the action is not there.

* The install steps say what Android now asks for. From 2026-09-30, phones in Brazil, Indonesia, Singapore and Thailand put an app from an unverified developer through an extra flow: Developer options, confirmation of the device lock, a restart, then a 24 hour wait before it installs, and after that a window of 7 days or indefinitely. Every release is an update, so it comes round again once that window closes. The README says so, and says that installing over adb skips it. It also now says that 640 MB is the manager's default rather than a measured minimum.

* You can cap how many times a day the hold lets you through. Until now the way out on the countdown was either always there or, with Lock today's budget on, gone entirely. The new row sits between the two: pick a number and the control counts down, saying how many are left, then goes once they are spent. Zero, the default, changes nothing. The count survives the app being killed and comes back when the day starts over.

* A saved gate override no longer costs 19 MB on every launch. The check that refuses an override whose type TikTok's own catalogue disagrees with used to load the whole catalogue, 17,393 entries, and hold it for as long as the app was running, on launches where the Feature Gate Lab was never opened. It now loads the one thing it reads, a type per AB key, which measured 1.9 MB against 19.1 MB. The Lab screen still loads the whole thing when you open it, and refuses exactly what it refused before.

* With a SIM preset on, TikTok no longer reports your SIM changing. Swap a SIM, or change which one carries data, and a startup check sends one event: how many SIMs the phone has, how long since it last looked, and the country and carrier before and after. The preset covers the country and the carrier, so both halves read as the preset, but the SIM count comes from a part of Android the preset never reached, and the event still says the hardware changed. With a preset on the check is skipped. Nothing else in the app reads what it stored.

* "Resume videos after scrolling" works on the Following and Friends tabs. TikTok kept a position for every feed it played, then handed one back only for a short list of places it built into the app, and the Following tab is not on that list. The switch looked broken there while the position was being stored all along. With the switch on, any feed that has a stored position now uses it.

* Every number row says what it accepts, and says so when it moves what you typed. Twelve of the fourteen stated their range nowhere: type 5000 into "Daily time budget" and it came back "Current: 600" with no explanation. Each row now carries its range under its own wording, and a number outside it is reported rather than quietly pulled in. The seven rows whose text says zero turns them off read "Current: Off" at zero instead of "Current: 0 videos", which was a limit of none rather than no limit.

* Each download destination dialog is headed by the row you tapped. Video, photo and sticker all opened a window titled "Download path", so nothing on the screen said which of the three you were editing, and the sentence under it stayed English on a German or Indonesian phone. Both follow the language now.

* TalkBack says which box is which in the five Min and Max dialogs. It read them as "edit box" and "edit box, Unlimited": the headings above them are separate views and the only hint on either field was a value rather than a name, and that one vanished the moment anything was typed. Both fields are labelled now. The sentence above them still says an empty maximum means no upper bound.

* A gate override whose type the catalogue disagrees with is refused on a fresh launch too. The check that catches a rule which would hand TikTok a word where its own code expects a number could only work while the catalogue happened to be loaded, and nothing loaded it until the Feature Gate Lab screen was opened, which is exactly the launch where the check was needed. Reaching that path now asks for the catalogue in the background, so one read goes through unchecked instead of all of them.

* Restoring an older backup keeps the settings it predates. Anything added since the file was written went back to its default, so a backup taken before the download destinations were split put your video, photo and sticker folders back to DCIM/TikTok without a word, and so did every other setting added since. A backup is a set of values to apply now, not a picture of the whole app: what the file does not mention stays as you have it, the restore and the undo both say how many that was, and a file carrying only the old single download folder fills in all three.

* The cached-feed filter stops working in a register TikTok owns. It read its answer into the register holding the cache payload and then jumped back into TikTok's own code at two points, both of which are also reached with a reference in that register. On this build each of those points overwrites it before anything looks at it, so the app was never wrong; on a build that reads it first the feed would have refused to load. The filter has a register of its own now.

* The comment image watermark keeps the position TikTok gave it. The patch read its own on/off answer into the register holding the watermark's x coordinate, then wrote a zero back before drawing. That is the same picture on this build, because TikTok moves the canvas first and draws at nothing but zero, and it would have pinned the watermark to the left edge on a build that draws anywhere else. The switch has a register of its own now.

* The patched app carries less code. Twenty classes inherited from ReVanced were compiled into the shared payload of every build and nothing in this project called any of them, including a color picker, a second settings backup, a network helper and an environment nag screen that had been switched off and left in place. They are gone. Nothing reachable changes.

* The two caption settings say so when a TikTok build drops them. Caption text size and the strip behind the captions found their views by a number baked into this project, which the next TikTok build is free to reassign, and both settings would then have done nothing while the Hook status row reported everything fine. They are looked up by name now, the way every other lookup here is, and a build without those names is reported under "captions" in the diagnostics.

* The five Min and Max rows show the range they actually hold. Reset settings, restore a backup or undo one, and rows like "Views" kept the old "20K to 1.5M" under them while the stored value had already gone back to its default. Only closing the dialog rebuilt that line; every path that changes the value rebuilds it now.

* "Hide comments with pictures" leaves your own alone. Your stickers and images disappeared from threads you were in, which reads as the comment having failed to send rather than as a filter doing its job. Other people's are hidden as before, and if nobody is signed in nothing changes.

* Undo is greyed out until there is something to undo. On a clean install it was offered like any other row, and tapping it said the settings could not be restored, which reads as a breakage rather than as an empty drawer. If it does somehow run with nothing saved it now says there is nothing to undo yet.

* Saving a gate configuration writes what is on the screen. Rotating the phone, changing the text size or theme, or coming back from a deeper screen rebuilt the page and kept the fields from every earlier version of it, so a save could collect text from boxes nobody could see. Those pages are also released now instead of being held for the life of the app, and a custom value box left open when the screen goes is closed with it.

## 0.25.0 (2026-09-08)

* A Lab change that fails halfway no longer erases what the Lab had recorded. Saving a set of overrides, resetting them, undoing that, or restoring a backup puts the previous configuration back when the write fails, and the record of which gates had actually fired was thrown away with it, so the detail screen reported "not triggered" for gates that were.

* An override profile written somewhere other than the Lab's own export applies on a Turkish phone. Turkish capitalises i to a dotted letter, so a lowercase `int` in the file folded to a different word than the catalogue's, and the rule was refused as a type mismatch on that phone and no other.

* A gate override that replaces a whole configuration object is worked out once instead of on every read. TikTok reads some of these constantly, and each read used to parse the stored value from scratch, up to 64 KB of it, on the thread asking for the gate. A value nested past a sane limit is also refused now with a message saying so; the check that appeared to do that sat on a method that never recurses and so could never fire.

* Comment translation stops doing work for batches it never asked for. The hook that notices a finished translation sits on TikTok's own completion path, so it ran for every batch in the app, and with the feature switched off it still walked the fields of two objects and took a lock on that thread each time. A request already in flight when the switch goes off is still finished properly.

* Turning clear display on by itself no longer looks to TikTok like you asked for it. The patch stops the events its own code sends when clear display changes, and two of the three it was written to stop had not matched anything in this version of TikTok for some time. Both were looked up in a way that gave no sign when they were missing, so the patch reported success and sent the events anyway. The patched app now silences four of those events where it silenced one.

* A video reached through TikTok's translated-video path now gets the same treatment as any other. The hook that prepares a video for download was installed on the last way out of the method and there are two, so one route returned a video this project had never seen. That is fixed, along with three more hooks that covered a single exit and would have missed the others on a build that returns from more than one place.

* The download path redirect checks the four instructions it replaces before replacing them. It looked at two of them and deleted four, which on a build that lays that run out differently would have removed unrelated code with nothing said. Writing the check found that the five places it runs are not identical after all: four append "/Camera/" and the fifth appends "/Camera".

* A patch that cannot apply to a future TikTok build now says what it was looking for. Fourteen checks threw an error naming nothing, so a failure read as a crash in the patcher rather than as the patch reporting a missing anchor. The telemetry description also promised to stop location uploads that the supported build does not contain.

* Five more injections and eight register checks stop assuming the shape of TikTok's own methods. Every register is now read off the instruction it belongs to, and the settings row this project adds took three working registers on trust: one of them was still in use, which a check now catches. A parameter that holds a long or a double occupies two registers and was being counted as one, so eight checks meant to prove a spare register existed could pass on a method that had none.

* Follow diagnostics keeps working when TikTok's own code moves. Eight registers were written out by hand, and every anchor it looks for was optional, so a build that renamed one would have left the patch reporting that it applied while logging the wrong thing, or crashing the app's own network layer. Each register is now read off the instruction it belongs to, and a missing anchor fails the build with a message naming it.

* A gate override whose type the catalogue disagrees with is no longer handed to TikTok. It only mattered on one path, where TikTok holds no cached value and so there is no type to check the rule against: a text rule on a key the app reads as a number came back as text, and the app crashed in its own code rather than in anything this project added. The Lab's detail screen says why the rule was refused. The catalogue has to have been loaded for this to bite, which today means the Lab screen has been opened.

## 0.24.0 (2026-09-08)

* The Save button on a sticker is in your language. It was the one piece of text this project adds to TikTok that never went through the translations, and it could not simply be translated: the button's own English label was also how the code recognised its own button, so a German one would have been added a second time on every sheet. It carries a marker now, and reads "Medien speichern" or "Simpan media" where it should.

* The daily hold works with a screen reader. It stopped touches and nothing else: it went up without being announced, "Open the feed anyway" was read as ordinary text rather than as a button, and the feed's like, comment and share controls behind it could still be swiped to, which is the one thing the hold is there to stop. The hold announces itself now, its way out is offered as a button, and what it covers is out of the way until it lifts.

* The Feature Gate Lab no longer freezes while it saves. Turning overrides on, and saving or resetting one gate, wrote to storage on the thread that draws the screen, so the tap sat there until the write finished. If a settings restore was running at the same time it sat there until the restore finished. All three now work the way the Lab's other changes already did: the write happens in the background and the screen catches up.

* Comment translation stops rebuilding one of TikTok's own services over and over. Working out which language you read in, and which languages you asked not to be translated, meant building that service and looking for the right method. The answer was only remembered when the method was found, so on a build that does not have one it was built again for every comment in view, while holding the lock that TikTok needs to hand a finished batch back. It is asked once now, and once is enough either way.

* Every row in a settings dialog list keeps Hushfeed's check mark. The list was restyled once, just after it opened, so any row you had to scroll to reach had never been touched and came up with Android's own check mark on the wrong side and TikTok's text color. The eight row "Included diagnostics" picker scrolls on a small screen and on every screen at large text. Rows are styled as they appear now.

* The patches keep applying when TikTok's own methods grow. A call Hushfeed injects can only name sixteen registers, and a method with enough locals pushes its arguments past that, which fails the patch with an error that says nothing about registers. Thirty five injections were written the short way and would have broken on the first TikTok build that crossed the line. They now use the long form only where they have to, so nothing about the current build changes, and a frame that cannot be patched at all says so in those words.

* A sticker save no longer keeps the screen it started from in memory. The job held the Save button, and a button holds the whole screen behind it, so closing the sheet freed nothing until the save finished, up to two minutes later with eight more saves queued behind it. It holds the button weakly now and simply skips handing it back if the sheet has gone.

* Saving an animated sticker as a video can be given up on. If the phone's encoder stopped producing frames partway, the save sat in a loop that neither the cancel nor the two minute limit could reach, and one of the three background workers that save media was gone until the app was killed. Two more stickers after that and saving stopped working entirely, with nothing said. The loop now gives up the moment the job is cancelled or runs out of time.

* Translators can work in Weblate. The settings strings were one tab separated file per language, a shape Weblate cannot host, so translating meant editing a file in a pull request. A language table can now be either that file or the comma form Weblate exports, and the generator writes the list of source strings a Weblate project translates from. A table that has been through a spreadsheet is read as well, byte order mark and all. Nothing changes for anyone already editing the tab form.

## 0.23.0 (2026-09-08)

* Back keeps working on the settings screen when TikTok moves to the newer back gesture. The screen's Back rode entirely on a method Android stops calling once an app opts into predictive back, which TikTok has not done yet and will. The Feature Gate Lab already handled it; the settings screen does now too, through the same code.

* The patch bundle is byte reproducible. Two builds of the same commit used to differ, because the manifest recorded the moment it was built, so nobody could rebuild it and check the published checksum against their own. That field is pinned to the commit now, and a rebuild produces the same file down to the byte.

* The README says which Morphe Manager you need. Manager refuses a bundle built against a patcher newer than its own, so on 1.28.0 and older this one simply would not load, and nothing said so. It also now says in plain terms that everything Hushfeed adds runs inside TikTok with TikTok's permissions and data, and that this repository and its GitHub releases are the only official source.

* Comment translation notices every batch finishing, not half of them. TikTok signs off a finished batch from two different places, and Hushfeed was only listening to one, so batches that finished the other way were never marked done or failed. Depending on which way TikTok went, those comments were either never translated or asked for again on every scroll.

* A very wide animated sticker no longer saves as a black video. The converter checked how many pixels a frame held but not how big a picture the phone's graphics chip will take, which on a lot of Android phones stops at 4096 a side. A frame past that failed to upload, nothing was reading the failure, and the file came out black. It is refused now, and the sticker is saved in its original form instead.

* The blocked word lists take two operators as well as plain phrases. Put two phrases in quotes and join them: "cat" & "dog" hides only what has both, and "cat" !& "dog" hides what has the first and not the second. It works on blocked caption words and blocked comment words. Plain phrases mean exactly what they always did, a comma inside quotes is part of the phrase rather than a separator, and a line you started and did not finish is refused while the box is still open instead of quietly matching nothing.

* A settings box with a long explanation no longer squeezes the field it explains. At twice the system text size the explanation could take the whole dialog. It scrolls and gives way now, and the box keeps its full height.

* Turning "Thumbs down blocks the commenter" off now takes effect on comment sheets you had already opened. The takeover only went one way, so a comment row TikTok had kept in memory went on blocking, and a screen reader went on offering to block, until that memory was reused. Every row hands the control back the next time it is filled in.

* "Start today over" can be taken back. It ends a running hold and forgets what has been counted, and it used to leave nothing at all behind, so a mis-tap cost you the rest of the day. The row now says "Tap again to put the counts back", the same bargain the seen video history row already made. The offer lasts until the day turns over, because after that the counts belong to a day that is finished.

* The rows that do something no longer look like the rows that open a page. Reset settings, Undo, Start today over, Clear the seen video history and Clear diagnostic data all wore the same ">" as a row that opens a screen. They have dropped it. Back up and Restore keep it, because those really do open a file picker.

* A new switch turns the daily budget from advice into a commitment. Leave "Lock today's budget" off and nothing changes. Turn it on, and when today's budget runs out the hold has no "Open the feed anyway", "Start today over" is refused, and the budgets, the reset hour and the switch itself cannot be edited again until the day starts over at your chosen hour. You can turn it off freely any time before the budget runs out, and turning it on after the budget has already gone locks the rest of that day too. The lock lets go on its own when the day turns, and only then: moving the phone to another timezone does not end it early. It survives the app being killed, and messages, profiles and search keep working the whole time.

* The settings screen no longer draws its first row under the status bar and its last under the navigation bar. TikTok targets a recent enough Android that every window is edge to edge whether the app asks for it or not, and the two calls that used to color the bars stopped doing anything at the same time. The screen now measures the bars and the display cutout and moves its content clear of both, with its own background reaching behind them.

* Comment translation stops hammering TikTok when a batch fails. A comment list binds its cells many times a second, and every one of those binds asked for the same failed batch again, with an exception logged each time. It now waits two seconds, then eight, then thirty, and after a fourth failure leaves that batch alone until the comment list loads again. A batch the service is working through a few comments at a time is not counted as failing.

* A comment batch that only half translates is asked for again. If the service came back with ten of thirty comments, the batch was marked done and the other twenty were never retried.

* If a TikTok update moves the field the translated comments arrive in, comment translation switches itself off for the session and says so in the log, instead of reading every batch as a failure and retrying each one three times.

* A settings restore from a backup made for another TikTok version could be quietly undone the next time you opened TikTok. The restore worked, and then the startup check that exists to finish interrupted changes read it as unfinished and put the old settings back. It now leaves a finished restore alone.

* A settings backup that only half downloaded says so. It used to come back as the same unexplained refusal as a photograph or a file from a newer Hushfeed. Files that are unreadable or too large reach you with their own wording too, which they never did before, because the check that produced the wording ran after the point where those files were already rejected.

* Undoing a restore tells you when it left the Feature Gate Lab rules out, the same way importing already did.

* Searching the settings finds the rows that do something, not just the switches. Back up, Restore, Reset, Undo, Hook status, Export diagnostic report, Clear diagnostic data, Included diagnostics, Clear the seen video history and Start today over all answered "No matching settings", which are exactly the rows people go looking for when something has gone wrong.

* A settings backup keeps working when Hushfeed moves to a new TikTok version. The file carries a note of which TikTok build it was made for, and that note used to refuse the whole file, so on the day this project retargets every backup anyone held would have stopped restoring. Only the Feature Gate Lab rules in it depend on the TikTok build, so those are left out and the toast says so; your settings come back either way. And a refused file now says which way it was wrong, so a download that was cut short reads differently from a backup written by a newer Hushfeed, instead of both saying only that it was rejected.

* A Feature Gate Lab rule saved at the wrong moment could do nothing at all. If TikTok happened to be reading its configuration on another thread while you saved, the rules it had already read were written back over your change, and the change stayed invisible until the next one. Reads made before TikTok has finished starting no longer queue up behind each other either.

* The Feature Gate Lab was making the whole app slower even with nothing switched on. TikTok asks itself hundreds of configuration questions a second, from every thread, and the Lab sits on the answer to each one. It was taking a single lock on every one of those before checking whether it had anything to record, which it does not unless you selected the Feature Gate Recorder, so every thread in the app queued behind every other. It checks first now and takes the lock only when there is something to write down.

* With either feed button on, the app was laying its whole screen out again on every frame, on the feed and everywhere else, for as long as it was open. Putting the buttons in place asked for a fresh layout even when nothing had moved, and the thing that puts them in place runs on every layout, so each one asked for the next. They are placed only when a position actually changes now, and not at all while the feed is off screen.

* On Android 6 the app could go down where a caption was resized, where the feed filters emptied a batch and said so, and where a creator pattern ran too long to finish, and the Hook status row quietly recorded nothing at all. Several calls and three types in the code that ships inside TikTok only exist from Android 7 onward, and both the compiler and the tests run on a desktop Java where all of them exist, so nothing ever said so. They are replaced with equivalents that work on every version the app installs on, and the build now runs Android's own API level check over both payloads, so the next one of these stops the build here rather than on a phone.

* Two more things a long press can do: copy the link to the video, or copy the link to the sound it was made with. The video link gets the same cleaning a shared link gets, so the parameters that say who sent it do not travel to the clipboard.

* New patch, Enable voice comments. TikTok builds the recording and publishing entry points behind a gate that not every account is on. It ships off, so selecting it in the patcher is the switch, and nobody here has been able to try it on a real account yet. Ported from icysymmetra's Metra patches.

* New patch, Comment sort controls. TikTok has a full comment sort sheet with hot, newest, media and creator options, and decides who gets it with a rollout gate and a per-post check. With the switch on you get the sheet TikTok already builds, on every post. Ported from icysymmetra's Metra patches.

* Automatic video advance now also shows TikTok's own Auto scroll action in the video actions panel. That entry hangs off a second flag, so accounts outside TikTok's rollout never saw it however the feed gate answered.


* Every patch in the bundle is now verified against a real TikTok 46.2.3 APK rather than a stripped fixture. All 70 apply, checked again on 8 September 2026, and the README records the exact build and its checksum so anyone can reproduce the same run.

* A hold no longer argues with a phone call over the sound. It asks once as the panel goes up, and again only when you come back to the feed, instead of asking every second for as long as the hold lasts.

* The diagnostic report names the exit of the app itself rather than whichever of TikTok's background processes the system happened to reap last, and it carries the exit status and importance alongside the reason.

* A feed filter that emptied a single batch says "one batch" rather than "1 batches".

* A settings backup and a diagnostics report made in the same second are now named with the same timestamp. The backup used local time and the report used UTC.

* Three search boxes read back what you typed instead of their own label. A content description on an editable field replaces its contents for a screen reader, so typing "cats" came back as "Search settings".

* The tab picker rows say whether they are on, and say so again after you tap one. The check box in each row is not the thing you press, so a screen reader had no state to read at all.

* A second undo banner within six seconds of the first now gets its own six seconds. The first banner's timer was still queued and took the new one away early, along with its Undo.

* The published patch list names what each patch actually depends on instead of saying "BytecodePatch" for all of them. Every patch that adds a setting now requires the Settings patch too, so the patcher cannot leave you with switches on a screen that was never built.

* The Long-press controls description in the patcher now mentions saving the original sound, which it has done since 0.21.0 while the description still listed three actions.

* The README says why saving works the way it does. It reads the addresses the app already fetched, on the session you are already signed in with, so there is nothing pretending to be a browser and nothing to keep in step with the site.

* Wording fixes across the settings and the messages the app shows you. A budget of one no longer says "That is 1 videos today". The hour the day starts at reads as a clock time rather than "13 o'clock". The confirm-a-tap message is a whole sentence in German and Indonesian instead of a translated phrase with an English verb dropped into it. A settings backup is named with a date and time you can read rather than a run of digits.

* Seven defects in the translation tables, including a placeholder that only worked while there was one value to fill it, five German summaries missing the full stop the rest of their screen has, and a quote that opened in German and closed in English. A new check refuses all three shapes from now on.

* Blocking someone from a comment now updates every comment of theirs on screen, not only the one you pressed. A screen reader was told the others were not blocked, and following that told it to unblock instead.

* A hold really does quiet the feed. It gives the sound back when you leave the feed for messages or search, and if a call takes the sound away it asks for it again rather than giving up for the rest of the hold. A video finishing behind the hold no longer counts against the automatic advance limit or puts a notice over the panel.

* Two settings pages stop offering rows for patches that are not in the bundle. The download destinations and filename templates need the downloader, and the operator code and name need the SIM patch.

* Clear all in the inbox reads as something you can press rather than as another heading.

* Two patches that could be selected on their own put their switches somewhere nobody could reach them. Hide comment popup ads built a switch on a page that never appeared, and the offline videos limit sat below an early return belonging to the Downloads patch, so selecting it alone gave a page without it. Every settings page now keeps each patch's rows behind that patch's own flag.

* Clear all in the inbox is readable in both themes. It was picking its color from a flag the settings screen sets, which away from that screen answers for the system theme rather than TikTok's own, so it could draw dark red on a dark sheet.

* The feature gate report says more precisely what it replaces, instead of implying every value is redacted.

* A hold now actually holds. The panel covered the feed and swallowed touches, but the video underneath kept playing with sound, the feed kept advancing if automatic advance was on, and every video that went by behind the panel was counted against the day. The hold asks for the audio focus so the player stops, automatic advance stands down until the hold ends, and nothing is counted while the feed is out of sight.

* The daily budget really is free when it is switched off. The hold check ran from the player's progress callback several times a second and took a lock and built a calendar every time, whether or not a budget was set. Now the off case is two field reads, and with a budget set the day is worked out once a day instead of once per callback.

* The release check now fetches the address in the bundle index every time it runs, instead of only when the checkout happens to hold a freshly built bundle. Pointing the index at a release that does not exist yet is caught before the push rather than by someone whose Manager cannot fetch it.

* The four feed buttons work in a right-to-left layout. Their positions are pixels worked out from where you drag them, and a mirrored layout was throwing those away, so the buttons piled up on each other and would not move sideways.

* The countdown on the hold panel is readable again. The panel is always a near-black scrim, so the countdown no longer follows the settings theme onto it.

* Two patches no longer depend on another patch being selected to be usable. Custom offline videos limit sets its own flag, so its two switches appear when it is selected and stay away when it is not, instead of following the Downloads patch. Hide BdTuring CAPTCHA popups now brings Hide CAPTCHA popups with it, because that is the patch that owns the switch it reads.

* The feature gate report says in the file that its values are redacted, so a gate marked changed showing the same text twice reads as the redaction rather than a mistake. The bundle description no longer claims more redaction than ships: the Lab own value export is a file you import back, so it keeps what it read.

* Every settings page with something on it has a row into it. The page and the row used to keep separate copies of the same condition and two of them had drifted, so a bundle with the block author patch but none of the four playback patches could reach the daily budget only through search, and Hide comment popup ads on its own had a switch no page would show. Each row now asks the page itself. The Playback row also counts a set budget as an active setting.

* The feature gate recording is redacted before it leaves the app. It is the one file the issue templates ask people to attach, and gate values are server configuration, so addresses and per-install identifiers were going out in it while every other export here was already cleaned. The values are redacted and the structure is not, so it is still a readable report, and the Lab itself still shows what it actually read.

## 0.22.0 (2026-09-07)

* The block button, the sound and Not interested buttons, the undo banner and the daily budget hold come back after TikTok rebuilds its main screen. The extension kept the first activity it was handed for the life of the process, so anything drawn over the feed went to a window that was no longer on screen, and nothing noticed because a rebuilt screen is destroyed rather than finishing.

* An AAC sound is saved as AAC. It shares its frame sync with MP3, so the reader that tells containers apart called it an MP3, which left a file the gallery would not play. The two layer bits separate them.

* Saving the original sound on Android 6 to 9 says what is wrong. Those versions write a real file, and without storage permission the save failed only after the sound had already been fetched, with nothing to act on. It is asked before anything is downloaded now, the way every other save here asks it.

* The original sound and animated stickers are fetched over TLS only. Both read their addresses straight out of a server response and took whatever scheme was in them, while every other saver here already required https. A cleartext mirror is a body anyone on the network can choose, and the sticker one is handed to a decoder written in C.

* Leaving the Feature Gate Lab while a reset, an undo or an import is still running no longer breaks it. The change finishes on the main thread and put the switch back without checking the screen was still there, so it crashed, and because that happened before the busy flag was released, every later Lab change was refused with a message about one already running until TikTok was restarted.

* A settings backup can no longer take the app down when it is restored. A Lab rule holds its structured value as a string, and the depth check on the file around it said nothing about what that string contained, so a deeply nested one overflowed the stack. That is an Error rather than an exception, so it walked past every catch on the restore path and killed the process. The value now goes through the same bounded reader the rest of the file does and is refused as invalid instead.

* Saving the original sound works at all. The fetch only ever accepted an MP4 container, and TikTok hands back an MP3 for many sound addresses, so every one of those was refused as an unsupported format and the long press said the sound could not be saved. What arrived is now read from its own header, and the file is named and typed to match, so an MP3 lands as .mp3 and plays. Ogg, WAV and FLAC are recognised too.

## 0.21.0 (2026-09-07)

* Long press can save the original sound. That is a different file from the video's own track, which is the finished mix cut to the length of the post: the sound entry is the whole thing, as it appears on its own page. It is named after the sound rather than the post, so the same song saved from two videos is one file, and it goes to Music beside your other sounds. A video with no sound entry says so instead of doing nothing.

* The hold on the feed stops above the row of tabs. It covered the whole screen, which took the tab bar with it, so messages and search were not reachable after all. It measures the bar rather than guessing at it, and covers everything on a build where it cannot find one.

* A short animated sticker with a single frame saves as a GIF again. Whether a WebP moves is settled by the flag in the file rather than by counting its frames, which cannot tell a still from a one-frame animation.

* Playback has a Start today over row, for a budget you set and immediately regret. It clears what has been counted today and ends any hold, and leaves the budgets themselves alone.

* Saving a sticker tells you where it went, not where it asked to go. The gallery renames a duplicate, so saving the same sticker twice used to report a path with nothing at it.

* The daily feed budget no longer costs anything when it is switched off, which is how it ships. It counted and wrote to storage on every player report whether or not a budget was set, several times a second. Nothing is counted now until a number goes in, the record is written on a background thread, and the watched time is committed in steps rather than on every report.

* Moving the hour the day starts at, or crossing a timezone, no longer hands back a budget that has been spent. Raising a budget now also lifts a hold that was running under the old one, and a hold no longer charges you for the videos playing behind it.

* The way out of a hold is on the hold itself. It was on a banner drawn underneath it, which meant it could be neither seen nor tapped.

* The budget settings only appear where the hook that feeds them was applied. They used to show on any build with a Playback patch, take a number and count nothing.

* The settings screens are checked at twice the text size and in a mirrored layout, on every page a reader opens rather than one of them, in both themes.

* The five feed count filters take a number the way the feed writes it. Type 20K, 1.5M or 2B instead of counting zeroes, and the row reads the range back in the same form. Nothing is rounded: 1.234M is exactly 1234000, and anything the field cannot read is refused with a message rather than saved as a number nobody meant.

* A download can no longer come out as name_2 when nothing of that name was in the folder it was saved to. The check that produced the suffix was looking in TikTok's private staging directory, which has nothing to do with where the file ends up, so it could only ever be wrong. The gallery settles a name that is already taken, and it does that without a race.

* Saving a still sticker as a GIF is refused rather than written out as a one-frame animation. The model calls anything whose type merely contains "webp" animated, and the decoder reports a still picture as one frame, so between them a plain picture could reach the animation path. The bytes now decide it, and the converter refuses anything with fewer than two frames.

* The Hook status row names the surfaces that are missing something instead of saying only that something is. It reads correctly when one surface has reported, its report is redacted and cleared along with the rest of the diagnostic data, and it follows the "Included diagnostics" choice like every other section. A surface that has stopped counting says so. None of this costs the feed anything: a lookup whose answer is already known no longer takes a lock while you scroll.

* A comment without a thumbs down no longer makes Diagnostics report the whole build as broken.

* There is a daily budget for the feed, off unless you set one. Give it a number of videos or a number of minutes and it says so once when the day is used up. Give it a hold as well and the feed goes behind a countdown for that long, while messages, profiles and search carry on working and nothing in the feed is thrown away. The day starts at four in the morning by default, and you can move that. Both counts and any running hold survive the app being killed. This is separate from the auto-advance limit, which only ever counted videos Hushfeed itself advanced past.

* The converter behind "save an animated sticker as a GIF" is covered by tests for the first time. It now has checks that the frame count and every frame delay survive the conversion, that a patch which replaces what is under it really clears that area first, that a frame asking to be cleared afterwards is, and that a still picture is refused rather than written out as a one-frame animation.

* Diagnostics has a Hook status row. Patches attach to code TikTok renames on every release, and until now a hook that lost its anchor failed quietly while the switch above it still read on. The row shows one line per surface: how many of the things it looks for are in this build, how many are not, and the first one that went missing. The exported diagnostic report carries the same table.

* Two settings-backup messages are translated again. Their wording gained a full stop at some point and the tables kept the older version, so a failed backup or restore spoke English on a German or Indonesian phone.

* The shared payload is 18,032 bytes smaller. It carried an About screen that nothing ever opened, which came with a browser view that ran scripts and loaded a page over the network, and two keep rules for libraries this project does not use.

* Undo on the seen video history now answers even when a newer clear has taken over, instead of leaving the row waiting on a reply that was never coming, and it says so. Watching videos also stops running a whole-table cleanup after every single one.

* Thirty-three translations for wording the app no longer uses have been removed, and the backup screen's undo row is translated again after its wording changed and left its translation behind.

* A caption file that is nested unreasonably deeply is now refused with a reason instead of killing the download in progress, and saving a video whose frames are unusually large no longer fails on Android 8 and older. Ordinary captions and downloads are unchanged.

* Settings search no longer treats an accent typed on its own as a match for everything, and it folds each setting's words once when the page opens rather than on every letter you type. Diagnostics also stop filling with a line for every page of search results when logging is off, which was pushing out the events around a crash.

* Choosing a download folder now sticks. The folder picker is its own screen, so Android often rebuilds the settings screen behind it, and the rebuilt one had forgotten which setting was waiting. It took the folder you chose and did nothing, without saying so. The waiting setting is now remembered by name across the rebuild, and a folder that arrives with nothing waiting says so instead of vanishing.

* The player's block, hide and Not interested buttons now say what they are in your own language. They are drawn as glyphs, so their spoken labels are all a reader using TalkBack gets, and those three still read English on a German or Indonesian phone. A build check now covers every spoken label the way it already covered messages.

* A saved subtitle no longer takes the whole download down with it. The gallery decides the video's filename and can hand back one with no extension, and cutting that name at its dot threw, which the download path reports as the save having failed even though the video was already on disk. The subtitle now keeps the whole name.

* A diagnostic report no longer carries the videos and comments you were looking at. Reports already hid addresses and credentials, but the id of a video, comment or message went through untouched, and each of those opens a post anybody can read. Both the named ids and the bare lists the feed probe prints are now removed before a report is copied or saved. The counts and timings that make a report worth reading are kept.

* The thumbs down on a comment blocks only the account you pressed. Where a press started was kept on a single object shared by every comment on screen, so a second finger, or the list reusing a row between the press and the release, could let a release act on a comment it never belonged to. Each control now keeps its own press, and a release with no press behind it does nothing.

* Saving a slideshow posted by an account with a very long name no longer stalls. Each photo's name is cut down to fit the filesystem, and the counter that tells one photo from the next was the part being cut, so every photo after the first landed on a name that was already taken and the search for a free one never moved on. A shortened name now carries its counter on the end, and the search gives up and keeps the original name rather than counting upwards forever.

## 0.20.0 (2026-09-07)

* An interrupted Feature Gate recording is discarded before the next baseline is taken, so a session stopped by its setting cannot make later reads look changed. The isolated boundary test now passes on its own as well as in the full suite.

* Automatic advance now has an optional per-session video limit. It counts only visible completions from Hushfeed-owned scrolling, keeps manual and native-only advance independent, stops with a brief notice, and resets when the feed component is recreated. Runtime coverage is 460 tests.

* Advanced downloads can hand a sanitized link to YTDLnis with its documented audio or video type and optional background mode. The profile controls activate only for `com.deniscerri.ytdl`; generic package names still receive a plain link, and an unavailable target leaves TikTok's save in place. Runtime coverage is 458 tests.

* Feature Gate Lab boundary tests now cover malformed persisted scalars, disabled profile imports, master and reset cycles, recorder enablement and concurrent observation limits. Translation retry state expires cleanly, and SIM preset matching keeps null current values and unsupported region fallbacks safe. Runtime coverage is 454 tests.

* Deep feed and account checks now cover repeated response caching, late and final follow delivery, cached and offline fallback policy, hard-filter preservation, bounded probe rotation and malformed verdicts. Every supported account-write route keeps its challenge visible, and the 160-event diagnostics cap is reserved atomically when callbacks arrive together. Non-finite or malformed status codes remain unconfirmed. Runtime coverage is 446 tests.

* Worker-backed settings and Feature Gate Lab tests now drain their owned executors before asserting, reset per-sandbox state before each case, and keep the region semantics check separate from the API ICU cross-check. Runtime coverage is 412 tests.

* Native boundary coverage now exercises structured numeric coercion and overflow, URL scheme refusal, destination roots, media fallback and frame bounds, plus direct navigation, share, LIVE, sound and translation policy shapes. Codec playback and final container behavior remain native-device checks. Runtime coverage is 434 tests.

* Global-layout hooks now detach a previous root when a replacement activity has no content view or resolvable author identifiers. Author decorations are restored when installation cannot continue.

* Video overlay layout passes now reuse their id, visibility and match buffers instead of allocating traversal containers each time. A synthetic 200-pass trace over 80 cells measured 57.68 ms before the change and 56.09 ms after it.

* Feed, inbox, share and sticker helpers now share resource lookup, global-layout ownership, reflection caching and publication cleanup. Replaced roots detach their listeners, dynamic-module misses keep retrying, and legacy sticker saves keep unique filenames. Runtime coverage is 409 tests.

* The extension payload no longer carries unreachable APNG conversion, retired language-editor or Lab preference code. Declaration-only fingerprints and unused compatibility metadata are gone, and the keep rule now covers only extension classes while preserving injected and reflective entry points. The shared payload fell from 229,024 to 221,720 bytes and the TikTok payload from 1,377,012 to 1,361,300 bytes.

* Release checks can now fetch the indexed Morphe bundle and its SHA256SUMS entry, comparing both with the locally built artifact before an index is promoted.

* Repeated fully filtered feed batches now explain the top rejection reasons without exposing post data and offer a direct link back to Feed filter settings. Empty server responses do not trigger the notice.

* Share sheet settings now discover observed video actions and show readable labels with stable identifiers. The checklist keeps unknown manually saved identifiers, updates the same hidden-action list, and leaves native share delivery and target identity unchanged.

* Feed filtering can now hide posts older than a chosen number of days. The switch is off at zero, and posts with missing, zero, future or overflowing timestamps stay visible. Quality fallback never restores an age-rejected post.

* The player now has a local creator-hide action. It records the current stable creator id without calling TikTok, skips that creator on later feed batches, and offers a searchable one-entry-at-a-time list with undo.

* Settings now have a translated search page. It matches titles, descriptions and category names, hides controls from unselected or unavailable patches, and opens the existing section at the matching preference without creating duplicate editors.

* Settings headers, captions and editor labels now keep Android's font padding, so large text has room for its full glyphs instead of clipping at narrow widths. Preference rows and native editors remain content-sized for 1.3x and 2x text.

* Native settings lists now use one selection indicator. Single-choice rows draw a radio at the leading edge and multi-choice rows draw a checkbox there, while the platform list adapter still owns checked state, selection and dismissal.

* Numeric settings now label their values as seconds, days or views per like where those units apply, with singular and plural wording in English, German and Indonesian. Existing stored values and unitless controls keep their behavior.

* Support forms now capture the TikTok package and version, Morphe and Hushfeed versions, selected patches, affected surface and ordered settings changes. `validate-release-facts.ps1` checks those public release facts and the runtime test count before an index is published, rejects failed, errored or skipped test cases, and the bundle description explains that diagnostic exports redact signed URLs and credentials.

* Release dependency resolution now disables `mavenLocal()` after the Morphe settings plugin adds its own repository, unless `-PallowMavenLocal=true` is supplied. The reviewed graph is recorded in Gradle verification metadata with SHA-256 checksums, and the wrapper distribution checksum now matches Gradle's published 9.7.1 binary while the smali and Morphe patcher pins remain intact.

* Comment translation checks the switch again before a delayed native call, reserves one request per loaded batch, retries failed completions and remembers only successful ones. Expiration uses elapsed time, generation allocation is serialized with reservations, and cleanup resolves a late failure or completion against the retired request before it can clear a newer retry.

* Diagnostic file exports now build and publish on the shared worker pool. The save reports null streams and failed publication, returns the provider's actual destination, and uses unique app-document files on Android 9 and earlier so repeated reports remain available.

* Interrupted media work now lives in an extension-owned cache namespace. Startup reconciliation removes stale temporary files and only pending MediaStore rows recorded by Hushfeed, while completed media and unrelated TikTok cache stay alone.

* Media saves now share a bounded worker queue. Transfers have byte, deadline and free-space budgets, transient mirrors retry with capped backoff, request data is snapshotted before enqueueing, and cancellation removes unpublished work without falling back to the UI thread.

* Media downloads keep trying later mirrors when an earlier URL is malformed. Each mirror owns its parsing, connection and transfer errors, aggregate failures omit signed query data, and a failed run removes a partial temporary file.

* Media retries now treat socket timeouts as recoverable, keep one deadline across every part of a queued save, and cap unusually large Retry-After values. Sticker saves try every supplied mirror and cap both downloaded bytes and decoded image pixels. Pending gallery inserts are journaled before insertion, and failed cleanup stays recorded until the provider confirms deletion.

* The settings journal now survives an AtomicFile backup left by a crash, compares Lab rules without depending on their display order, and covers edits made from the Lab detail pages. Seen-history loading can retry after a clear races a failed open, reset takes its journal lock before capturing defaults, and detached exports still clean up the selected document.

* Feature Gate Lab imports are now bounded while they are decoded. Raw JSON and gzip exports use strict UTF-8 and duplicate-key checks with depth, node, string, array and rule limits, and a failed export only removes the selected new document while reporting cleanup failures.

* Settings and Feature Gate Lab changes now share an on-disk operation journal. An interrupted write is reconciled at startup to the prior or committed state, the private Undo copy is retained, and malformed recovery records produce a visible notice.

* Seen-history undo stays available until its SQLite transaction commits. Rejected inserts and write errors report failure on the settings row, while a failed first database open clears its in-memory load gate so the next read can retry.

* Patch verification now compares names with ordinal matching, accepts only JSON booleans, fixes the TikTok target in the script, and treats an out of memory process as a failed measurement when the CLI did not exit cleanly. Block status parsing also rejects fractional values that only round to an integer.

* The extension payload now keeps its Android 23 promise. Download workers, history, feed state, Lab sorting and language selection use APIs available on the declared floor, and the TikTok module declares that floor explicitly.

* Patch verification now treats the desktop process, result report, requested patch set, target version and saved APK as one result. Heap measurements use the same checks, keep expected out of memory runs visible, isolate concurrent runs, and never remove a path outside the work directory.

* Blocking only shows the undo action after TikTok returns an exact status of zero. A missing, malformed or uncertain response leaves the current view alone and reports that the result could not be confirmed. The same rule applies when undoing a block from a comment.

* Diagnostic exports scrub ordinary selected events as well as crash reports. Signed URLs, token assignments and credential-bearing exception text are removed before copy or file export, and copied reports are marked sensitive for Android versions that support the clipboard flag.

* Settings restore reports whether the two preference stores returned to their prior state. Rejected backups, complete rollback and recovery-required failures have separate feedback, and a verified Undo copy remains available when recovery is needed.

* Persistent settings and Feature Gate Lab data now accept writes only from the package's main process. Secondary processes can still read current values, but stale snapshots cannot overwrite newer settings or consume migration state.

* Incorrect settings text was fixed. The thumbs down block claimed it needed a restart, which it never did. The two region switches claimed one and never asked for it, and now they do, because TikTok reads the country at startup. A dozen summaries that talked about secure window flags, native seekbars, candidates and pages now say what actually happens.

* Every message the patches show you is now in your own language, not just the settings screens. Blocking, saving, seeking, the confirm taps and the backup actions all spoke English on a German or Indonesian phone. The ones that name something, a handle or a file, are built so the translation decides the word order rather than having English word order with the name dropped in.

* German and Indonesian corrections. The German CAPTCHA message told you to turn off a switch by a name that is not on the screen, the speed list said to use commas as decimal points when the field only takes dots, and shares were translated as arithmetic division. Indonesian had the Custom offline videos row reading as its own limit row, and For You left in English where TikTok's own Indonesian build says Untuk Kamu. Six German titles that were long enough to be cut short have been shortened.

* Three things about the settings screens. The comment search box follows your phone's theme, has a border so it looks like a field you can type in, gets a search key rather than a newline, and is a proper size to tap. Each section says the same thing on the home page as it does at the top of its own page, where the two used to disagree. And the Diagnostics page keeps the backup actions together at the end instead of scattering them between the logging switches.

* The undo on the block banner is a real target now, announces itself to a screen reader, and says Undo in your own language. Clear all on the suggested accounts row is translated too, and readable on a light inbox rather than washed out.

* Turning off the profile picture or story save gives TikTok its own long press back. Both took over the gesture while switched on and then kept it after being switched off, answering that they had not handled it, which is not the same as handing it back. Holding a story is how TikTok pauses it, so that one was worth getting right.

* Four bounds on what a download will accept from the server. A sticker is read with a ceiling instead of until the phone runs out of memory, a mirror that is not an HTTP address is skipped rather than abandoning the ones after it, a file over 2 GB has its length checked properly, and a filename built from a very long name is cut to what a filesystem takes without splitting a character in half.

* Handing a video's link to another downloader now strips the tracking from it, the same way sharing a link does. TikTok's own link carries parameters that say who sent it, and those were going out with the hand-off even with Sanitize sharing links turned on.

* The Friends tab gets filtered like the rest of the feed. It arrives as its own kind of response, so none of the existing hooks ever saw it and sponsored posts kept turning up there with every filter switched on. Ads, and LIVE cards when you have livestreams hidden, are dropped from it now.

* The Save button on a sticker saves the sticker you are looking at. TikTok reuses the preview sheet for whatever sticker you open next, and the button was holding the one it was built for, so opening a second sticker and saving it could write the first one instead.

* Settings only shows switches for patches you actually selected. Show seekbar and the two sharing-link controls were drawn whatever you picked in the patcher, so a bundle without them offered three switches that did nothing and the App behavior row claimed two were on before you had touched anything. The count beside each section is also more honest now: it was ignoring the mobile-data quality cap and three of the comment switches.

* Crash reports stop carrying addresses and session tokens. The reports TikTok's own crash handler produced were already scrubbed, but the ones from the patch's Java handler were not, and a network exception puts the whole request URL in its message. Those reports go to shared storage and to the clipboard so they can be attached to a bug report, so anything in them travels. Both paths now go through the same scrub.

* Saving a video no longer falls over when one of its captions names no language. TikTok sends the language as free text, and a value that cleans up to nothing but separators used to slip past the unknown-language fallback and then crash the save outright. It is treated as unknown now, like an empty one always was.

* Clearing the seen video history keeps its way back when you tap twice quickly. The record is read off the database on a background thread, and a second tap that arrived before that finished used up the offer and left nothing to put back, which on a cold database is exactly the window the toast invites you into. The offer now stands until the copy is really there, and the row says so. Putting the record back also keeps a video you watched again in the meantime at its newer time instead of the older one.

* The box above the comments now actually turns up. It was being added from the callback that fills a comment row in, and a list fills a row in before it puts it on screen, so the code went looking for the comment sheet from a row that was not in it yet and found nothing. It waits for the row to land now. It also looks a little further up for something to sit above, because the list is not always a direct child of the column that stacks the sheet, and a search you typed on one video no longer follows you to the next one.

## 0.19.0 (2026-09-06)

* A box above the comments that narrows them to what you are looking for, by what a comment says or who said it. Nothing is taken out of TikTok's list: comments that do not match are collapsed where they sit, so clearing the box brings them all back and paging, replies and counts never know anything happened.

* Review pass over the last three switches. The refresh rate one was overwriting the register TikTok stores its own record of the rate in, so it now replaces the store outright and leaves the value alone; it also only declines a request that is slower than the screen, because not every one of the six places that asks is asking for less. Duet and Stitch was skipping the commercial refusal as well as the creator's, which is the one most likely to get a post taken down, so only the creator's is answered now and its description is accurate again. A muted save no longer downloads a sound it will not use, no longer fails outright when that download fails, no longer quietly saves with sound when the stream is missing, and on Automatic keeps the file TikTok would have saved rather than jumping to the largest one. Saving a story asks the other app too.

* A switch that ignores the creator's Duet and Stitch setting, so the entries appear on videos that closed them. Only that one check is skipped. A photo post still cannot be duetted, a private video still cannot, and the app's other reasons for refusing all still stand. Whether the upload is accepted is the server's call and not something a patch can decide, so treat this as making the button available rather than a promise the post will stay up.

* Videos can be saved without their sound, and the save button can hand the link to a downloader you already use instead of saving anything here. Put that app's package name in the box and the button opens it with the video's link; leave it empty and nothing changes. Saving the sound as its own .m4a still works alongside the muted save, so you can have the picture and the sound in separate files if that is what you wanted.

* A switch to stop TikTok pulling the screen down to the frame rate of the video. When a video starts, the app asks the window to run at that video's rate, so a 30 fps clip puts a 120 Hz phone at 30 Hz for everything, scrolling included. With the switch on the window says it has no preference and the phone decides. It does not raise anything, it only stops the asking.

* Review pass over the three new switches. The edge seek was moving whatever the player last reported, which after scrolling on to a photo post is the video before it, so a press did nothing visible and swallowed TikTok's own hold as well: the seek now names the video it means. Its zones were also measured across the display rather than across TikTok's window, which in a side by side split view made the whole screen a forward-seek. A video fitted to the screen says it sits in the middle, because one large enough to fill did not have to. Fitting also measures the window TikTok has rather than the whole display, which is the case the switch exists for.

* The whole of a video, on screens that are not 9:16. TikTok picks a size for the video that fills the window, which on a folding phone opened up, a squarer screen or a split view means the sides or the ends are cut off. The switch takes the same arithmetic and the smaller scale, so the video sits inside the window whole. A tall phone looks exactly as it did, because the video already fits there.

* Two notifications TikTok gives you no way to turn off. One switch drops the "somebody started following you" notification before Android is asked to post it, which is the usual complaint when a wave of empty accounts follows you overnight. The follower still turns up in the Inbox and nothing else in the drawer changes, because the app tags each notification with the channel it belongs on and only that one is dropped. The other switch takes away message streaks: the streak button on a chat and the reminder that goes with it.

* Press and hold the left or right third of the screen to jump back or forward. How far is up to you, five seconds to start with, and the middle third still does whatever Long press is set to. It rides on the Long-press controls patch and is off until you turn it on, because holding a video is also how TikTok starts playing it at double speed.

## 0.18.0 (2026-09-06)

* Review pass over the story save, the search suggestions and the share host. Hiding the suggestions was guarding two methods that build the request rather than the two that make it and show it, so the page fetched exactly as before and only the display was dropped: it now guards the request and the hand-off to the screen, which is what the switch said it did. A story is kept with the play area that bound it, because the viewer binds the pages either side of the one you are looking at and a single last-seen story would be the neighbour's by the time you pressed. The story save also runs the quality, sound and subtitle settings the same way a feed download does, says what it did when a photo story stops part way, and hands the press back to TikTok when it is not going to save anything. The host box refuses anything that is not a host, since a "?" in it would have left every shared link pointing at a front page. The search switch was also unreachable unless another Interface patch happened to be installed.

* Shared and copied links can go through a host of your choosing. Put one in the box under Sanitize sharing links and tiktok.com is swapped for it, which is how you get a link that shows a preview where TikTok's own does not. Only TikTok's own hosts are swapped, and only the host: the rest of the link is untouched and nothing is sent anywhere.

* A switch for the searches TikTok puts on the search page before you type. It stops the app asking for them as well as showing them, so the rows are not fetched and then hidden. What you have searched for yourself is a different list and is left alone.

* Stories can be saved. They are ordinary posts with no save button anywhere on them, so press and hold one and it goes to your usual video or photo destination, photo posts and all. Holding a story is also how TikTok pauses it, so the switch is off until you ask for it.

## 0.17.0 (2026-09-06)

* Review pass over the sticker formats and the profile picture save. The profile hook was reading the user through the very method it had been added to, so every profile open burned a stack and swallowed the overflow. It reads the field now. The long press is only taken when the feature is on, so TikTok's own is left alone otherwise, and a picture is refused rather than saved when the handle on screen belongs to somebody else. A sticker with more colors than a palette can hold no longer counts every one of them: 64 frames of noise went from running out of memory to under a second.

* Press and hold a profile picture to save it. TikTok never puts the full size avatar on screen, so the file comes from the profile the app loaded rather than from what is drawn, which means you get the original rather than a display crop. It lands in your photo destination, named after the account.

* Animated stickers save in the format you pick. The old behaviour turned every one into an MP4, which messaging apps that take animated stickers reject. WebP now writes the file TikTok sent, byte for byte, and GIF is written here rather than handed to a converter app. If a conversion fails the sticker is still saved as the WebP it arrived as, so a save never comes back empty handed.

* Review pass over the settings lookup and the sound save. A phone set to two languages reads them in order again, so French and then German shows German rather than English: the new table lookup was only reading the first language, which is not what Android does with resources. Saving the sound no longer downloads the same bytes a second time; it takes them from the file the video download already has, which also means the .m4a and the video carry the same audio stream.

* Advanced downloads can keep the sound on its own. Turn on Save the sound as well and every video you download also writes an .m4a named the same way, copied out of the track TikTok already serves rather than re-encoded. Android 10 and later file audio separately from video, so it goes to Music under whatever folder name your videos use.

* The settings screen speaks Indonesian. All 404 strings are translated, and the tests now walk every screen under the Indonesian locale the same way they do under German, so a missing line fails the build rather than showing up as English on a phone.

* Patching fits the memory Morphe Manager gives by default. The settings text used to be merged into TikTok's own resources, which meant decoding a resource table of 74,765 strings and needing 768 MB to do it. The translations now travel in the extension's code instead, and screen capture detection, which only wanted the manifest, no longer asks for resources at all. The default set of patches finishes in 512 MB. AMOLED dark theme still rewrites colors, so picking it still means raising the limit to 768 MB.

* Review pass over the new filters and the history clear. Clearing the seen video history now copies the rows off the database rather than out of memory, which could be empty when the feature has never run in that session: the clear deleted everything and offered nothing back. A blocked creator entry that opens a pattern and never closes it no longer swallows the names after it, patterns stop at the end of their line, and each pattern keeps its own compiled form, so two of them no longer take turns handing each other the wrong one.

* A switch hides the numbers under the like, comment, favourite and share buttons while leaving the buttons themselves.

* Clearing the seen video history happens on the tap, with no dialog first. The row says what it cleared and a second tap puts it all back, in the database as well as in memory.

* The install steps say what to do about an out of memory error while patching. The same work needs more than Morphe Manager's 640 MB default on a desktop, and 1024 MB is comfortable.

* Comment tools can hide comments that are a picture or a sticker instead of words, replies included. It works on its own, without the keyword filter.

* Clear display now hides the tab strip by asking whether the controls are actually hidden, rather than reading a stored setting the automatic path never writes. The strip stayed up in exactly the case the change was meant to fix.
* The search filter leaves a page alone when every card on it looks like an advert. A whole page of adverts is less likely than one of the card shapes being wrong, and an empty grid tells the user nothing.

* Two more feed filter lists: only from these countries, and never from these countries. They read the same value the author row shows, and a video TikTok sends without one is always kept.

* A blocked creator entry between slashes, like /^news_/, is a pattern instead of one handle. It is matched against the @name and the display name, and a pattern that will not compile is dropped and said once rather than quietly doing nothing.

* Feed filter takes ranges for comments, favourites and shares as well as views and likes. They read the same statistics the app already sends with every video.

* Each of the six buttons in the right column has its own switch: the avatar and follow button, like, comments, favourite, the music disc and share. Hiding the whole column is still one switch.

* Clear display keeps the top tab strip away. TikTok hid it when the mode started and brought it back on the first swipe; it now stays gone until the tap that ends the mode.

* Show the handle instead of the name puts the creator's @username beside a video in place of the display name they chose. It works alongside the country switch, which still follows whichever of the two is showing.

* Sponsored cards are gone from the search Top and Videos grids. Search results are not videos but cards, so the card's own ad flag decides, along with the multi-ad, AI and brand zone shapes and the video it wraps.

* Review pass over the CAPTCHA gate and the follow verdict. The request recorder is its own dependency now, so selecting only the BdTuring patch cannot leave the gate blind and hiding every check. A verification request whose scene cannot be read is shown rather than hidden. Follow paths on a relation route count as follows. The verdict reader no longer keeps a context per request when diagnostic logging is off, and cannot throw into TikTok's network stack. A status code nested under `data` no longer answers for the whole response, a static getter is not read as this response's verdict, and 0, "0" and "0.0" all read as accepted. Both windows use the monotonic clock, so a corrected system clock cannot open or close them.

* New patch: Hide comment popup ads. Commenting with a word or emoji an advertiser has bought sets off a full brand animation over the comment sheet. The switch stops it.

* Playback quality has a second choice for mobile data. It is a ceiling, so it lowers what plays on a metered connection and never raises it.

* Ads no longer come back in the profile video viewer. Opening a video from a profile was filtered, but scrolling past it refilled the viewer through the profile detail panel's own two delivery methods, which nothing was watching.

* Follow diagnostics ships. The patch was in the tree but hidden, so nothing built it, which is why nobody could attribute a follow that quietly failed. It now reads the server's `status_code` and `status_msg` off the answer, says once per session when TikTok refused a follow and why, notes whether a puzzle was hidden just before, and writes the whole exchange to the diagnostic report when logging is on.

* Hide CAPTCHA popups no longer hides a puzzle the server raised over a follow, like, comment, repost or story action. Those puzzles gate the action behind them, so hiding one made the action fail with nothing on screen. Every risk check is now classified by the request it belongs to, in one place shared by all five hooks, and a hidden check is logged with its id and says so once per session.

## 0.16.0 (2026-09-06)

* Settings now use AMOLED backgrounds, grouped rows and larger page headings. Each section has its own icon, with active counts on the home page.
* Feature Gate Lab, gate details and recording reports follow the same layout. Switches, value pickers and long labels stay readable in both themes.
* Native preference actions and saved values stay intact. English and German labels wrap with the phone's text size.
* Updated the settings screenshots and added navigation, value-saving and theme coverage to the local runtime tests.

## 0.15.1 (2026-09-06)

* The repository is public. Morphe Manager can add it as a source straight from the project page.
* Build tooling refreshed: Gradle 9.7.1, guava 33.7.1 and androidx annotation 1.10.0. The Android Gradle plugin and Kotlin come from the Morphe patches plugin (AGP 9.1.0, Kotlin 2.4.10), and the patcher stays at 1.12.0 because that is what the stable manager ships. Unused version pins were removed from the catalog.
* The bundle is otherwise the same as 0.15.0.

## 0.15.0 (2026-09-06)

* The project has its own name. The bundle, the entry in TikTok's settings and the settings screen now say Hushfeed. The repository moved to SysAdminDoc/hushfeed, the source index points at its releases, and the about row links to the project page instead of a donation link.
* Settings backups are written with the hushfeed-settings tag. Backups and the undo copy made under the old tag still restore.
* Everything listed under 0.14.1 ships for the first time in this release.

## 0.14.1 (2026-09-05)

* The settings screen follows the phone's language. German ships with this release, and a translation table plus a generator make the next language a single file. Falls back to English for anything untranslated.

* New Long-press controls patch. A long press on a video can keep TikTok's own action, do nothing, or open the comments. Off by default.

* Hide video overlays gained switches for the caption, the music line, the action column on the right, the survey cards and the status bar. Each puts its element back when the switch goes off.

* New Hide content warnings patch plays videos TikTok has classified without the overlay asking to be tapped through first. Off by default.

* New Show author region patch puts the country a video was posted from next to the creator's name on the feed. Off by default.

* Not interested, the follow and like confirmations, double tap to open comments and subtitle tracking keep working when the block button is switched off. They all read which video is on screen, and that reading was tied to the block button's own switch.

* The block button now blocks the creator of the video you are watching. It was following the feed's prefetch instead, so it usually targeted the account one video ahead. The same fix reaches Not interested, the follow and like confirmations, double tap to open comments, the sound button and caption tracking, which all read the same thing.

## 0.14.0 (2026-09-05)

* Preserve exact numeric tokens in settings imports and reject hidden trailing data. Backup restore and Lab undo use the same parser.

* Feature Gate Lab applies its master switch, resets and loaded-value imports without confirmations. Reset works while overrides are off, and the menu can undo the last reset or import. Migration and restart guidance use notifications.

* Keep inbox category models available so disabling a hide switch restores the row and its height on the next layout, including Archive and Shop.

* Reject incomplete settings backups using their saved key inventory. Recovery attempts both preference stores independently when a save fails.

* Fixed the shared text importer rejecting complete JSON and partially applying malformed profiles. Fractional or overflowing whole numbers are rejected before preferences change.

* Added settings backup, restore, reset and undo in Diagnostics. Backups include Feature Gate Lab rules and switches. Files are validated before changes, and failed saves attempt to restore the prior values.

* Added feed filter regression tests for content labels, paid content, verified authors and blocked sounds. They cover empty metadata, independent switches, exact sound ids and case-insensitive sound phrases.

* Inbox system switches now use bound category data in every language, including rows already on screen. Recycled rows recover their original height, and conversation titles do not trigger system filters.

* Added persistent automatic video advance using TikTok's native completion handler, with pause and dialog checks preserved. Disabling the option stops the scrolling it started.

* Added a default playback speed for each new video and custom speed menu choices through 3x. Manual choices remain active for the current video, and the native menu state follows the player.

* Reject malformed country input and preserve locale scripts and extensions when a legacy variant needs fallback handling.

* Settings that need a restart now save immediately and show a notification instead of a restart dialog.

* Keep all subtitle languages when native metadata supplies a Unicode language name or no language code. Distinct names get distinct filenames.

* Added locale, timezone and native region overrides using the SIM preset. Store-region overrides are separately opt-in. SIM hooks now handle high registers and ignored return values safely.

* Added an optional side-by-side comment layout for wider windows, with a configurable width threshold.

* Added SRT subtitle saves with language selection and matching video filenames. Caption size, background and visibility in clear display are configurable.

* Cancel automatic clear display immediately when focus or its settings change. Save large feature gate reports as JSON with a bounded preview and clipboard error handling.

* Added an option to allow screenshots and Circle to Search by removing secure window flags and disabling the native search block.

* Added Feature Gate Recorder with Start and Stop controls, a gate-read diff and a copyable report.

* Added automatic clear display with a configurable delay. Manual restoration cancels the timer until the next video.

* Added playback quality choices for regular and adaptive video streams. Playback limits leave the full set of download variants available.

* Selected-quality saves now pair adaptive video with its matching audio track before writing the final MP4.

* Added download quality choices and direct Photo Mode image saving. Separate video and audio tracks are combined without re-encoding. Original photos keep their source bytes and format, with fallback URLs tried when a server fails.

* Fixed stale confirmation rings after a video change and removed old ownership when a comment control is recycled.

* Added a double-tap choice for the feed: normal behavior, no action, or comments for the current video.

* Optional second-tap protection now guards the feed Follow button and like heart with a red ring and a four-second timeout.

* Feed rules now support caption phrases, exact creator handles or ids, maximum length, promotional music, LIVE replays and views per like. If quality limits reject a whole page, retain its closest eligible video.

* Share sheet tools can hide sharing apps, video actions and the complete Send to row before the panel is built. Hidden names also accept stable action keys.

* Prevent a rapid feedback tap from racing the completion of the previous request.

* Feed overlay controls now follow the detail page lifecycle and disappear when a grid video is closed.

* AMOLED dark theme adds an optional black background palette with a patcher color picker.

* **feed:** add a Not interested button beside the block control. It uses TikTok's own service and reports success only when the response says the feedback was accepted.

* **privacy:** require all story, profile and typing hooks. Remove the unsupported online-status claim and describe the Firebase screen hook precisely. Seen history now clears reliably, retains at most 10,000 videos and rejects the video just watched on refreshed pages.

* **build:** reject bundles missing a DEX payload or containing a stale patch list. The build instructions now put the Android task last.

## 0.13.0 (2026-09-05)

### Features

* **feed:** four upstream patches for the feed toolbar: hide the LIVE button, the search button, the follow plus under the avatar, and the save button. The LIVE one shares the existing Live entrance switch and stops the button before it is built

* **build:** move to Morphe patcher 1.12.0 and patches plugin 1.3.4, which is what Morphe Manager 1.29 ships. Typed patch options, sliders and color pickers are available to patches from here on
* **feed:** a Hide already seen videos patch. Watched video ids go into a local database and are dropped from later feed pages, with a retention setting and a clear button. Nothing leaves the device
* **feed:** switches for the playlist bar, the floating event badge and the cards TikTok inserts between videos, plus the countdown lock on short drama adverts, which now releases when ads are being removed
* **captcha:** a second CAPTCHA patch for TikTok's risk control dialog, answering the existing switch. Off by default, it never touches SMS or two factor verification, and it logs every suppression
* **privacy:** a Ghost mode patch that stops the reports telling other people what you looked at: story views, profile views, the typing indicator. Online status is unchanged
* **privacy:** a Disable telemetry patch that stops ByteDance AppLog, AppsFlyer, explicit Firebase screen reports and crash reporting at their SDK entry points. Off by default, and it silences TikTok's own diagnostics too
* **inbox:** the Archive and TikTok Shop rows are now stopped at their widget injectors, so they never lay out
* **inbox:** three patches that work on TikTok's widgets instead of the finished views: suggested accounts and the stories tray are never built (and suggested accounts is now covered on Activity and New followers too), and the Activity and New followers lists can show in full instead of stopping at View all

### Bug Fixes

* **block button:** draw the block symbol instead of setting it as text, so a font without U+2298 cannot leave a tofu box on the button

* **feed:** close the ad delivery gaps upstream found: profile grid videos, the detail page, late insertions, the cache delivery chain, play-lag inserts and reach-bottom cache all run through the filters now
* **feed:** the cached video switch is now "Filter offline fallback videos" and applies to the videos TikTok falls back on when the feed cannot load enough new items

## 0.12.0 (2026-09-05)

### Features

* **comments:** the thumbs down on each comment now blocks the commenter instead of disliking. The icon stays, the row dims and the icon tints while the account is blocked, a second tap unblocks, and the overlay block button is gone

## 0.11.0 (2026-09-05)

### Features

* **share:** new Share sheet tools patch: a second tap is needed before a video goes to a friend, and people, share targets or actions can be hidden by name, or the whole Send to row

### Bug Fixes

* **comments:** the block button never appeared because a comment cell is bound before it is attached, so its root view was not the window. The button layer now attaches once the cell is in a window

## 0.10.0 (2026-09-05)

### Features

* **interface:** hide the "Search this image" prompt over videos and the top left Live entrance, each with its own switch
* **comments:** replace the two finger hold with a one tap block button drawn beside each comment

### Bug Fixes

* **comments:** draw the dimmed row and the undo banner in the comment panel's own window, so blocking a commenter is visibly confirmed

## 0.9.0 (2026-09-05)

### Features

* **feed-filter:** skip videos by sound, with a block-sound button on the player and a name list for re-uploads
* **feed-filter:** switches for paid partnerships, AI generated videos, verified accounts, Series and playlist videos
* **comments:** hide comments by keyword or by account, and block a commenter with a two finger hold

### Bug Fixes

* **comments:** watch the two finger hold at the window rather than on the comment cell, whose clickable children swallowed every touch

## 0.8.0 (2026-09-05)

### Features

* **tiktok:** add a one-tap block button to the video player, with an undo banner and a drag-to-move position that is remembered
* **tiktok:** add a switch for each Inbox row and header control, and a Clear all control for suggested accounts

### Bug Fixes

* **block-button:** hide the button off the video feed, and keep it available on videos longer than thirty seconds
* **block-button:** keep the button on a video opened from a profile or search, hide it over a profile page, and drop it on cards that have no account to block
* **block-button:** treat a block reply with a non-zero status code as a refusal instead of a success
* **inbox:** stop hiding every conversation when only message requests are switched off
* **inbox:** match the suggested accounts section, which uses none of the row title ids
* **inbox:** resolve view ids once instead of on every layout pass

Everything below this line is the release history of icysymmetra/tiktok-patches-for-morphe, which Hushfeed was forked from.

## [0.7.0](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.6.1...v0.7.0) (2026-08-23)

### Bug Fixes

* **build:** complete feed model stubs ([053ce6c](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/053ce6cd064c16c1a06e87e10b76896da7c00a83))
* **clear-display:** preserve state across feed transitions ([a433fe0](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/a433fe03b98ecee8814a83f94aaef760222e538f))
* Merge branch `dev` to `main` ([#95](https://github.com/icysymmetra/tiktok-patches-for-morphe/issues/95)) ([1ba91a3](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/1ba91a3d45ffdd8ee237105b8dd3ad8853a7b7fe))
* merge dev into main ([3c81835](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/3c8183512dfb9dbe7b7e93979e5a419e428ff605))
* **playback:** persist explicit speed selections ([0f785fc](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/0f785fc8685c30793bf7ffea028d1223613f52c9))
* **settings:** make custom dialogs fit device screens ([7592339](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/75923397520ff45fde1b5f618c1de2010de422e0))
* **tiktok:** consume download filename mappings ([864fc15](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/864fc1526d51f819daea8e0730716d62a4c6b662))
* **tiktok:** cover direct Turing CAPTCHA dialogs ([27b2639](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/27b263920ffadaf2e27777c703c225f2e4f3ce40))
* **tiktok:** expand startup and runtime hook coverage ([bac0ba8](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/bac0ba8d2697a60d2c66d723fba75e211ca48a49))
* **tiktok:** filter cached feed insertions ([d30a6dd](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/d30a6dd37f9eaa1c1faecdf2b30b047707170860))
* **tiktok:** harden bytecode hook resolution ([95e0a3f](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/95e0a3f8d6c45e97eb44c3066bd201e2d9ab1843))
* **tiktok:** preserve swipe-lock playback speed ([dfbe2a5](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/dfbe2a55aaced183f97a457b494893c31cebf596))
* **tiktok:** prevent settings crash and expand crash reports ([08186e7](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/08186e77ba89d92debfcd319fe8fcc745e2a363b))

### Features

* **downloads:** support separate media destinations ([f4580c9](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/f4580c94b3b2c0d2d79c2bb0bfa6a544abeaedb3))
* **tiktok:** add repost and cached feed controls ([446ee90](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/446ee90123b118642fe3c50f5da9221d984667c2))
* **tiktok:** expand offline video limits ([35eff0e](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/35eff0e4f84ea69925bbddc71eb04c6d0bf5e66d))

## [0.7.0-dev.8](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.7.0-dev.7...v0.7.0-dev.8) (2026-08-22)

### Bug Fixes

* **tiktok:** preserve swipe-lock playback speed ([dfbe2a5](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/dfbe2a55aaced183f97a457b494893c31cebf596))

## [0.7.0-dev.7](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.7.0-dev.6...v0.7.0-dev.7) (2026-08-20)

### Bug Fixes

* **tiktok:** consume download filename mappings ([864fc15](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/864fc1526d51f819daea8e0730716d62a4c6b662))
* **tiktok:** harden bytecode hook resolution ([95e0a3f](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/95e0a3f8d6c45e97eb44c3066bd201e2d9ab1843))

### Features

* **tiktok:** add repost and cached feed controls ([446ee90](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/446ee90123b118642fe3c50f5da9221d984667c2))

## [0.7.0-dev.6](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.7.0-dev.5...v0.7.0-dev.6) (2026-08-20)

### Features

* **tiktok:** expand offline video limits ([35eff0e](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/35eff0e4f84ea69925bbddc71eb04c6d0bf5e66d))

## [0.7.0-dev.5](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.7.0-dev.4...v0.7.0-dev.5) (2026-08-16)

### Bug Fixes

* **build:** complete feed model stubs ([053ce6c](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/053ce6cd064c16c1a06e87e10b76896da7c00a83))
* **tiktok:** filter cached feed insertions ([d30a6dd](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/d30a6dd37f9eaa1c1faecdf2b30b047707170860))

## [0.7.0-dev.4](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.7.0-dev.3...v0.7.0-dev.4) (2026-08-13)

### Bug Fixes

* **clear-display:** preserve state across feed transitions ([a433fe0](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/a433fe03b98ecee8814a83f94aaef760222e538f))

## [0.7.0-dev.3](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.7.0-dev.2...v0.7.0-dev.3) (2026-08-11)

### Bug Fixes

* **playback:** persist explicit speed selections ([0f785fc](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/0f785fc8685c30793bf7ffea028d1223613f52c9))
* **settings:** make custom dialogs fit device screens ([7592339](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/75923397520ff45fde1b5f618c1de2010de422e0))
* **tiktok:** expand startup and runtime hook coverage ([bac0ba8](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/bac0ba8d2697a60d2c66d723fba75e211ca48a49))

## [0.7.0-dev.2](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.7.0-dev.1...v0.7.0-dev.2) (2026-08-11)

### Bug Fixes

* **tiktok:** prevent settings crash and expand crash reports ([08186e7](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/08186e77ba89d92debfcd319fe8fcc745e2a363b))

## [0.7.0-dev.1](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.6.2-dev.1...v0.7.0-dev.1) (2026-08-10)

### Features

* **downloads:** support separate media destinations ([f4580c9](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/f4580c94b3b2c0d2d79c2bb0bfa6a544abeaedb3))

## [0.6.2-dev.1](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.6.1...v0.6.2-dev.1) (2026-08-10)

### Bug Fixes

* **tiktok:** cover direct Turing CAPTCHA dialogs ([27b2639](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/27b263920ffadaf2e27777c703c225f2e4f3ce40))

## [0.6.1](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.6.0...v0.6.1) (2026-08-09)

### Bug Fixes

* **morphe:** support patching on older Android versions ([01463a0](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/01463a04e2a64a2a1400391778576779cfb2b211)), closes [#85](https://github.com/icysymmetra/tiktok-patches-for-morphe/issues/85)

## [0.6.0](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.5.0...v0.6.0) (2026-08-09)

### Features

* **tiktok:** restore download templates and sticker formats ([91d158c](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/91d158ca3da1126a2d2ca9f4c2330e81d3ea96f9))

## [0.5.0](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.4.1...v0.5.0) (2026-08-09)

### Bug Fixes

* **build:** select current bundle for patch catalog ([69bd1e9](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/69bd1e93194825b7af567e8456de4f29831fd9a8))
* **tiktok:** filter following ads at cached feed reads ([13d74f0](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/13d74f00b1d076aa22b7a4ab8bbe92bfaf2c4b6b))
* **tiktok:** filter late following feed ads ([a232cba](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/a232cbaeb3725f0c1361d20a99e9a6fd31a0be53))
* **tiktok:** hook native video looping boundary ([2721f01](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/2721f010b670842f9776b2afb34486de418f29d9))
* **tiktok:** preserve account verification CAPTCHA flows ([dd35d89](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/dd35d8948b002f0d098ed1f83a3f64cddc5f7904))
* **tiktok:** preserve native download addresses ([61da3c6](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/61da3c6413ed75cdb04f5eb1cb4b7292dfd06089))
* **tiktok:** repair automatic comment translation ([636ef64](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/636ef648c18be2610c6ee48beed7270e37ffce41))
* **tiktok:** reset completed video resume state ([100cab5](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/100cab58fc178f360c2e727308e161e3e3e2f9ad))
* **tiktok:** skip same-language automatic translations ([3415710](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/3415710facfc60db19943599112b9bc47cf82647))
* **tiktok:** use native video loop control ([f508adb](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/f508adbdbf6229e7a993a42616ed00cc0feadcd2))

### Features

* **tiktok:** add native search and seekbar controls ([c36bfaf](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/c36bfaf51d6e8d30fbef6e5c6ba99bfdeae283e2))
* **tiktok:** add playback completion controls ([9a51c2f](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/9a51c2f614145250177600144abd61eb30cf0841))
* **tiktok:** add quick-action controls ([fe29a24](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/fe29a249e42620d53dc6224c347c74aadc291c08))
* **tiktok:** port core patches to TikTok 46.2.3 ([2c52b6a](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/2c52b6aa1362a3a9ccbcdd8dad2e16ec4b9a06cc))
* **tiktok:** port Feature Gate Lab to TikTok 46.2.3 ([4bdbdee](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/4bdbdeed1649bdc6177ea6a2c62bd2d9300ac8d6))
* **tiktok:** redesign patch settings navigation ([a479bdb](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/a479bdb09a4e01584f9d683cdfda1c65d3ab6dff))
* **tiktok:** separate optional diagnostics from settings entry ([aad18c3](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/aad18c3e4b301366685174b0faa5a23a8a2850de))

## [0.4.1](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.4.0...v0.4.1) (2026-07-28)

### Bug Fixes

* **tiktok:** prevent seekbar recursion on stories ([9d2c0bc](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/9d2c0bc823ce661b385fff12945ac5c2ace0223f))

## [0.4.0](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.3.1...v0.4.0) (2026-07-26)

### Features

* **tiktok:** add feature gate lab and UI suppressors ([72bf32a](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/72bf32aa6991257ace0bab91d3a8767617cbd802))
* **tiktok:** add translation language exclusions ([8f60185](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/8f60185434a3cbb13653438a6561e2d7fc45f4dd))
* **tiktok:** enable Feature Gate Lab by default ([a164b70](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/a164b70cf046a738d3b0a817a57b1fedf515ef2e))
* **tiktok:** enable hold-and-slide 2x lock ([a2fd626](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/a2fd626ecb1fb116461b760445128bfdd8980f29))
* **tiktok:** improve feature gate lab search ([ffc19de](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/ffc19dece888a6ef814d20401d0bc4b9ba93858d))
* **tiktok:** port external browser patch from lyyako ([7a517c2](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/7a517c218a35508ac4f284450ff6d6c4d3ea05d8))
* **tiktok:** port publish date patch from lyyako ([1b6c386](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/1b6c386611239c39242bc813fca9614c8de3d3d1))
* **tiktok:** redesign support row ([cc39469](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/cc39469c98eabc0ccdea40fc423e94639c34703f))

## [0.4.0-dev.1](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.3.1...v0.4.0-dev.1) (2026-07-26)

### Features

* **tiktok:** add feature gate lab and UI suppressors ([72bf32a](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/72bf32aa6991257ace0bab91d3a8767617cbd802))
* **tiktok:** add translation language exclusions ([8f60185](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/8f60185434a3cbb13653438a6561e2d7fc45f4dd))
* **tiktok:** enable Feature Gate Lab by default ([a164b70](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/a164b70cf046a738d3b0a817a57b1fedf515ef2e))
* **tiktok:** enable hold-and-slide 2x lock ([a2fd626](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/a2fd626ecb1fb116461b760445128bfdd8980f29))
* **tiktok:** improve feature gate lab search ([ffc19de](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/ffc19dece888a6ef814d20401d0bc4b9ba93858d))
* **tiktok:** port external browser patch from lyyako ([7a517c2](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/7a517c218a35508ac4f284450ff6d6c4d3ea05d8))
* **tiktok:** port publish date patch from lyyako ([1b6c386](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/1b6c386611239c39242bc813fca9614c8de3d3d1))
* **tiktok:** redesign support row ([cc39469](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/cc39469c98eabc0ccdea40fc423e94639c34703f))

## [0.3.1](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.3.0...v0.3.1) (2026-06-17)

### Bug Fixes

* avoid concurrent feed list iteration crash ([275e8d5](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/275e8d5f3ad273b24a5bd592b0da86bfe7dc3ee4))

## [0.3.0](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.2.0...v0.3.0) (2026-06-17)

### Bug Fixes

* improve settings menu dark mode layout ([2aa837b](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/2aa837b165e16c3bf435b385f15160c0ad6c41d1))
* prefer largest clean download source ([4f63b8a](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/4f63b8aab765373041cbce77135efd55f34623c0))
* reduce repeated feed filter scans ([d161ef2](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/d161ef20c8d9648fb4e48d84655f062c9be99813))
* restore downloads fallback for restricted videos ([32cfafe](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/32cfafeaf10ad1bcf87d72a38927977f76000ec5))

### Features

* add navigation translation and settings controls ([72ad5de](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/72ad5de04b8f7c670f25ca718d219d06124fe553))
* add SIM spoof country presets ([bb73b3d](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/bb73b3dfebdcbbf9f612a7716e408a8928b56568))
* copy comments without username ([ac01d5f](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/ac01d5f8f2e673d6b72a38a6abf5d5da27330208))
* filter bottom navigation tabs ([e1f01b2](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/e1f01b25e35bb6d284691c273ae4b0b4778a57dc))
* save comment media downloads ([77b4dab](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/77b4dabcd6f327a7b9f06e5646193a0d7e54a52f))
* **tiktok:** add custom offline videos limit ([bfcb717](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/bfcb717d9e6fdcfb69ec7d0b30016352ed6d6c90))
* **tiktok:** add focused debug diagnostics ([878d0f4](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/878d0f44b4b1bc1846ecb888ca3f9536fcf4db11))

### Performance Improvements

* reduce feed filter scanning overhead ([ac13277](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/ac13277743e2c5f4022a4e1bafea1caf067520fe))

## [0.2.0](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.1.5...v0.2.0) (2026-06-06)

### Bug Fixes

* improve settings menu dark mode layout ([4da9d87](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/4da9d8785f0e6ad97f8c9ad7e4e3cf5655f2e0f4))
* reduce repeated feed filter scans ([370b806](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/370b806cc1c64574d767b397d612e79a58ed6896))
* restore downloads fallback for restricted videos ([277f400](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/277f4003643b35045b62f9f95f63ed2d8bb889bf))

### Features

* add SIM spoof country presets ([7f8fb32](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/7f8fb32dd7bc1f0b34078482216c087914b8ff4c))

### Performance Improvements

* reduce feed filter scanning overhead ([007d1be](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/007d1be0a93393a7ca1935cea8740cb167b5e6f9))

## [0.1.5](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.1.4...v0.1.5) (2026-06-01)

### Bug Fixes

* limit TikTok compatibility to global package ([49806ad](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/49806ad5a1c75bc2d7f052abf227b7d055879935))
* sanitize TikTok sharing links before shortening ([53a3558](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/53a3558f7e1921ccd2636fe2d322a4b7c1ca3d04))

## [0.1.4](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.1.3...v0.1.4) (2026-05-30)

### Bug Fixes

* improve settings readability and debug logging ([f4f4bc4](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/f4f4bc4dc9104182c1a419878ddf837057555340))

## [0.1.3](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.1.2...v0.1.3) (2026-05-29)

### Bug Fixes

* support global settings top row insertion ([c38f1b6](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/c38f1b63d32b3d7218de1f5c46243f728c6c5868))

## [0.1.2](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.1.1...v0.1.2) (2026-05-29)

### Bug Fixes

* fallback open debug settings insertion ([e201d53](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/e201d533d19504f4e32001d6985cad7ee9804b15))

## [0.1.1](https://github.com/icysymmetra/tiktok-patches-for-morphe/compare/v0.1.0...v0.1.1) (2026-05-29)

### Bug Fixes

* add release changelog script ([74fa14e](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/74fa14ebe975509816a9344031dd176a76595407))
* distinguish global and jp tiktok packages ([7e1f7df](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/7e1f7dfd23d79346529715854a9b1840af210d47))
* soften support preference copy ([5cbfd26](https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/5cbfd26030b35d5b2996ead5073b2ffd7df166a5))
