* 3.2.0
  * Recent keeps permission-blocked history visibly unavailable and removes it after restored access proves it unavailable.
* 3.1.0
  * A correctness and stability release. A few new reading options, much safer saving and backups, and a long list of fixes.
  * New:
    * Single Page Mode shows one page at a time regardless of the window size.
    * Choose the page fit policy, so pages fit by width, by height, or whole.
    * A Controls Hide Delay setting in Customize Controls picks how long the overlay stays visible.
    * When the selected text contains a link, the web search button becomes an Open Link button.
    * Searching now happens without leaving the document.
    * The first jump lands in reading history, so Jump Back returns you where you were.
    * The previous and next arrows wrap around at both ends in every search flow.
    * When every match is behind you the search wraps to the first one, and says so when there are none.
    * Double tap zoom now uses two states by default, with the old three states available in Advanced settings.
    * The fullscreen exit button reads Exit Fullscreen instead of Exit.
    * A file shared from another app now asks before copying when it is very large or space is tight.
    * That dialog offers keeping a copy, granting file access, or just reading it once.
    * Keep Copies of Shared Files becomes a choice between always keeping a copy and asking each time.
    * The theme toggle button stays visible when the PDF theme follows the system.
    * Tapping it then explains why it cannot toggle and lets you pick a theme right there.
    * The book title snackbar fits longer titles.
  * Library:
    * A document can no longer land in the library with an unknown page count.
    * A document already stored with the wrong page count is corrected the next time you open it.
    * Documents are identified by the hash of the first and the last MB of the file, so two files sharing a first megabyte no longer merge into one.
    * Files under two megabytes are identified by their whole contents.
    * Existing records convert quietly on open, keeping reading position and bookmarks.
    * Conversion is anchored to the real file, so a copy can never claim another book's history.
    * Home's relocate picker now heals the record even after the screen is recreated.
    * The reader's relocate picker verifies the picked file before repointing the record.
    * Backing out of that picker exits the reader again as intended.
    * Deleting a document now removes it correctly for every kind of file location.
    * Home no longer shows a stale list when two refreshes overlap.
    * Relinking the library no longer scans files on the screen thread.
    * A document with no address no longer displays the literal word null.
  * Saving:
    * Highlights made while a save is running are no longer thrown away.
    * Saving no longer reports success when the written file might be damaged.
    * The check now reads the end of the file, not just the start.
    * A failed save no longer retries in a way that can ruin a good file.
    * A spare copy is kept for password protected files too.
    * Saving a copy can no longer overwrite the file you are reading.
    * Updating a different file by mistake now asks you first.
    * Annotations can no longer be written to another document's chosen destination.
    * Saving no longer opens and re reads every highlighted page.
    * Saved files no longer grow with pages that did not change.
    * A saved file no longer balloons with a hidden copy of everything you read before saving.
    * Saving a file that was inflated by an older version shrinks it back to its real size.
    * Edits are refused politely while a save is running instead of being lost.
    * Deleting a document no longer races a reader that still holds unsaved highlights.
  * Highlights and Signatures:
    * Fixed highlighting in books where it silently failed.
    * Highlights no longer appear in a file that does not contain them.
    * A highlight that cannot be drawn now says so instead of pointing somewhere else.
    * Opening a highlight from the list centers the page counter and bookmark star correctly.
    * Dragging a selection handle is smoother and no longer redoes work on every touch event.
    * A signature no longer lands sideways on a rotated page.
    * Dragging a signature now moves it the way you drag it.
  * Reading:
    * Navigation History opens whenever the trail holds anything, even when every entry is ahead of you.
    * The page you are on is now tracked wherever the view moves, so the number in the bar is always the page you are looking at.
    * Jumping to a search result or a highlight now leaves you on that page, so leaving and returning brings you back to it.
    * Auto scroll now keeps the page number, the reading history and the saved position up to date as it goes.
    * Fullscreen now covers the camera cutout area instead of leaving a black band on notched phones.
    * Reading position is no longer erased when Always Open at First Page is on.
    * Right to left books no longer jump to the wrong end of the document.
    * Right to left books no longer save the wrong page when you leave.
    * Pages are centered instead of pushed to one side.
    * A document no longer turns a page by itself when it opens.
    * The first tap after opening toggles the controls instead of being swallowed.
    * Contents entries no longer show page 0 or jump to page 1.
    * Contents rows that resolve to nothing now show nothing and do nothing.
    * A finished one page book shows 100 percent.
    * Two page spreads show both page numbers when you jump.
    * First Page Alone shows as overridden while Single Page Mode is on.
    * Double tap zoom stays within your configured maximum zoom.
    * A drag cancelled by the system snaps the page and hides the handle.
    * A drag started right after a page turn is no longer ignored.
    * Auto scroll stops when you leave the reader and stays consistent afterwards.
    * Always Hide Margins now applies after you return from settings.
    * Rotate reads the real screen orientation, and explains itself when the system controls the window.
    * Rotation lock clears when you close the app.
  * Text Mode:
    * Going to a page now moves the text, not just the page number.
    * A page you jumped to stays put while the pages around it load.
    * Hyphen wrapped words read as one word.
    * Search boxes land on the right letters around ligatures.
    * Arabic search boxes use the correct character positions.
    * Text sizes and spacing are clamped to safe values when loaded.
    * Web search no longer crashes when no browser is installed.
    * Quote cards stay left to right for mostly English quotes.
  * Search:
    * Search next and previous can no longer freeze the screen, even in rare cases.
    * Repeated presses land on the right match every time.
    * Results are added to instead of being rebuilt from scratch.
    * A running search no longer holds the whole reader in memory.
  * Backup and Restore:
    * Backups keep working on Arabic and Persian phones.
    * Restore Pre Import Snapshot appears again after an import.
    * Backup cleanup keeps the newest files instead of the oldest.
    * An import stops safely when its snapshot cannot be found.
    * An import can no longer be interrupted midway by leaving the app.
    * The wipe warning now says that unsaved highlight recovery data and the stored signature are deleted too.
    * Imported settings are rejected unless they are known and correctly typed.
    * Two backups of the same name no longer corrupt each other on import.
    * The Auto Backup switch no longer reads on while nothing is scheduled.
    * The stalled backup notice now works for people who enabled automatic backup before it recorded a start date.
    * The offline dictionary no longer eats most of the system backup quota.
    * Backups record where a document came from, without changing the file format.
  * Privacy and Permissions:
    * Incognito stays incognito when the screen rotates while the file picker is open.
    * No lasting file permission is kept for a file opened in incognito.
    * Bookmarking a page in incognito now explains why it cannot, and offers to exit incognito.
    * Placing a signature in incognito no longer stores the drawn signature for later sessions.
    * Crash reports show the website instead of the file path.
    * A crash report is never sent unless you agree to it first.
    * Internal warnings about slow document work are no longer reported at all.
    * A report left over from an older version is deleted instead of sent.
    * A download that stops being encrypted part way asks before it continues.
    * A download that is not a PDF is refused instead of saved.
    * A download stops if it grows past a sane size or would fill the device.
    * Three internal screens can no longer be opened by other apps.
    * Android 10 and below now ask for write access as well as read access.
    * Copies written to public storage appear in other apps right away.
  * Home:
    * Back ends an active selection on the Folders tab instead of walking up a folder, matching the other two tabs.
  * Design:
    * Onboarding buttons no longer hide behind the three button navigation bar.
    * The home open button no longer collides with the navigation buttons.
    * The tip explaining how to leave fullscreen now actually appears, and stays gone once dismissed for good.
    * Release notes now appear for people who turned the Home page off.
    * Settings search says when nothing matched.
    * Renamed a label that two unrelated features were sharing.
    * Russian and Ukrainian delete warnings show the real count.
    * Clearing a single item no longer reports it in the plural, in every language.
  * Performance and Stability:
    * A page carrying an enormous amount of text now says so instead of killing the app.
    * Large scanned books no longer crash the reader on low memory phones.
    * Memory stays bounded no matter how long the book or how far you scroll.
    * The reader menu no longer slows the app down or pushes it toward a crash.
    * Fixed a rare crash while scrolling caused by preview images being reused mid draw.
    * Fixed a rare crash while cleaning up stored page previews.
    * Urgent page renders no longer get stuck behind background ones.
    * Cancelled renders restart instead of dying.
    * A cancelled page preview no longer keeps its page open.
    * A stale preview can no longer stick after an edit.
    * Highlights are no longer regrouped on every drawn frame.
    * Opening a highlight from the list no longer looks it up twice.
    * Text screens no longer leak a document when closed while still opening.
    * Go To Page no longer leaks its window and a document.
    * Reopening the same reader now honors the newly opened document.
    * An empty reader window no longer closes the window holding your document.
    * Tapping a page no longer searches it for form fields.
    * Low memory image allocation failures no longer crash the viewer.
    * A failed image decode no longer loses a reusable image buffer.
    * A missing gesture event no longer risks a crash while flinging.
    * Android 6 no longer crashes on online PDFs.
    * Android 6 no longer crashes while installing the dictionary.
    * Document loading no longer reads settings off the wrong thread.
    * Fixed a file reading bug that could feed the app garbage.
    * Fixed a mismatch between two parts of the low level code.
  * Development:
    * The two native dependency build scripts merged into one.
    * Building PDFium from source is now the default, with a prebuilt option.
    * Build a single processor architecture or all four.
    * A dry run mode previews the build before running it.
    * libpng and FreeType versions are pinned and their checksums verified.
    * The Gradle download is verified by checksum.
    * Crash report settings moved into the committed project config.
    * Fixed the crash dialog missing from store and F-Droid builds.
    * A release runbook was added and the setup docs refreshed.
    * The viewer library now ships its own shrinker rules.
    * Removed 45 unused texts from all 16 languages.
    * Removed nine dead color, size, and state values.
    * Split large functions into focused pieces.

* 3.0.0
  * The largest update so far. A new home screen, annotation tools, and a rework of nearly every part of the app.
  * Home Screen:
    * The app now opens to a new Home screen.
    * Opening a PDF from another app still goes straight to the reader.
    * Home has three swipeable tabs: Recent, Library, and Folders.
    * The selected tab is remembered.
    * The Recent tab shows a full width Continue Reading pager and a reading history list.
    * The Continue Reading card shows cover, title, progress, page count, and last reading date.
    * The pager swipes page by page between books.
    * The last reading date is right aligned on the card.
    * The Library tab shows reading status chips above a cover grid or list.
    * Reading statuses: To Read, Reading, On Hold, Completed, and Abandoned.
    * Setting a status marks books as read or finished independently from page progress.
    * Status chips wrap into two rows without scrolling.
    * Wide screens show all six status chips on one line.
    * Grid density options: small, medium, and large.
    * Sort the library by last opened or by name.
    * Long press multi select with batch status change and batch delete.
    * The Folders tab is a real file manager with breadcrumb navigation.
    * Folders offers a flat mode, per folder counts, and SD card volumes.
    * The Folders tab always shows the full device, before any scan setup.
    * The back button navigates up through the folder hierarchy.
    * A floating button opens any PDF through the system picker.
    * Search from Home covers the whole device, not just opened files.
    * Library matches appear first, never opened files after.
    * Home search opens as a full screen search page.
    * The home menu holds view, grid, sort, and folder mode controls.
    * View, grid, and sort controls appear only on the Library tab.
    * The folders view control appears only on the Folders tab.
    * Grid size hides when list view is selected.
    * The menu has icon and label rows: Stats, Scan locations, Open online, Settings, About.
    * The menu divider is hidden on the Recent tab.
    * A Library Stats dialog with Device, Library, and Reading Status sections.
    * Stats include storage used, favorites, pages read, and all six status counts.
    * Toggle strips color their selected option properly.
    * Segmented button coloring is shared between Home and settings.
    * All dates across the app render as year, month, day.
    * Home shows the document's real title instead of the filename, with an off switch.
    * Home can be disabled entirely, restoring the old picker on launch behavior.
    * A home button sits left of the PDF title in the reader toolbar.
    * The home button hides when the Home page is disabled.
    * Back from a Home launched document returns to Home.
    * Double tap to exit now applies only to documents opened from outside the app.
    * Double tap to exit is now disabled by default.
    * Pinned launcher shortcuts keep working across future app updates.
    * Books can be deleted straight from Home search results, and the results refresh immediately.
    * The Home app bar no longer darkens while scrolling.
    * Selecting multiple books keeps the app bar and status bar colors consistent.
    * The Recent tab shows the storage permission card whenever unreadable books need access.
    * Books fixable by the storage permission appear dimmed everywhere, and tapping one opens the grant flow.
    * Books missing for other reasons are hidden and return automatically once readable again.
    * After each scan, broken records re link to rediscovered files by content hash, keeping their progress.
    * Continue Reading only ever shows openable books.
    * The Continue Reading label shows the percent plus the current page over the total.
  * Library Cards:
    * One unified card design across the Recent, Library, and Folders tabs.
    * Cards show reading position, or total page count when unread.
    * Folder cards show the file size of every book.
    * Books opened through the picker now show page counts and sizes correctly.
    * Covers show reading progress as a ring with the percentage, or a thin bar along the bottom edge.
    * A Home setting picks the progress indicator style, with the ring as the default.
    * Progress bars no longer show dots at their ends.
    * An options button appears on every card, including never opened files.
    * Grid tiles get a three dots options button in the top right corner.
    * The last read date was removed from small cards.
    * More title spacing and a slightly larger info font on cards.
    * First page cover thumbnails with memory and disk caching.
    * Covers load with a cross fade and the cache trims itself.
    * A placeholder cover is shown for unreadable or password protected files.
    * Covers reload immediately when storage access is granted.
    * Covers and page counts work for files opened through the system picker.
    * Five badge switches with a List Card Badges dialog choose what list rows show.
    * Grid and list title line counts and the truncation position are configurable.
    * Selecting a book shows a bold check badge that stays visible on any cover.
    * Selected books gently dim, making the selection obvious at a glance.
  * Book Options Dialog:
    * Every card opens a per book options dialog.
    * The dialog shows a cover header, full PDF properties, progress, and a status picker.
    * Rename on disk and delete from device sit side by side.
    * The dialog fully works on never opened files, fetching their info in the background.
    * A reading status can be assigned to any file, opened or not.
    * Setting a status quietly adds the file to the library.
    * Rename and delete work for never opened files too.
    * A Remove from Recents action for previously opened books.
    * Hide from Library hides a book from the Recent and Library tabs.
    * Hidden books stay reachable through Folders and search.
    * The last read date is shown inside the dialog.
    * Borderless menu and properties icons without odd backgrounds.
    * Proper vertical padding around the dialog content.
    * Long press selection works on never opened files.
    * Batch status changes create library records for never opened files.
  * Library Durability:
    * Documents are identified by content, so progress and status survive moves and renames.
    * A moved file heals silently if the scanner finds it elsewhere.
    * Otherwise a relocate dialog lets you pick the file again.
    * Repicked files are verified by content, so reading progress is preserved.
    * If the reader fails to open a library item, it returns Home and starts recovery.
    * Files picked through the system picker keep working after a reboot.
    * Each opened document refreshes its stored location.
    * Records converge to real file paths as documents are opened.
    * The reader captures each document's title on open for the Home screen.
  * Scanning and Permissions:
    * Scanning never starts on its own. A setup card on the Library tab begins it.
    * Choose between scanning the whole device or only locations you pick.
    * A folder picker with breadcrumbs and tri state checkboxes selects scan locations.
    * The picker supports multiple storage volumes.
    * A Scan locations entry in the home menu changes the choice later.
    * Scans, background rescans, and the file observer respect the chosen scope.
    * Entries outside a narrowed scope are removed.
    * The scan scope filters only the Library grid, search, and stats.
    * File hashing and page counting stay within the configured scope.
    * Removing folders from the scope keeps their PDFs visible in Folders.
    * Whole device scanning keeps its full speed.
    * The library fills near instantly on a fresh install using the system media index.
    * A background scan catches anything the index missed.
    * The scanned index is saved, so every later launch shows the library immediately.
    * Expensive file processing happens silently in the background and never blocks the interface.
    * New downloads appear automatically while Home is open.
    * Deleted files are pruned safely.
    * Rescans skip unchanged files entirely.
    * Scanned but never opened files never pollute reading history.
    * Never opened files get page counts from the background scan.
    * All files access is requested with a proper explanation dialog.
    * The flow rechecks on return from system settings, with fallbacks for unusual devices.
    * A separate permission flow for older Android versions, including permanently denied handling.
    * Without access, Home still works in a degraded mode with a grant card.
    * The storage permission flow now works on Android 10 and below.
  * Dual Page Mode:
    * New Dual Page Mode pairs pages side by side like a book.
    * An optional First Page Alone setting keeps the cover on its own.
    * Paired pages fit two columns across the view, separated by the page spacing.
    * Taps, selection, highlights, forms, and signatures resolve the correct page of a pair.
    * Flings, page snapping, volume keys, and mouse paging advance one spread at a time.
    * Page indicators show a range like 4 to 5 of 120 for spreads.
    * The reading percentage counts the spread's last page.
    * The bookmark star lights when either visible page is bookmarked.
    * A quick action flips the mode in place while keeping the reading position.
    * Position restore remembers the layout mode.
    * If the mode changed in between, it falls back to a plain page jump.
    * Dual Page Mode is ignored in horizontal scrolling mode.
  * Reading Direction:
    * Right to left reading direction in horizontal mode.
    * The direction is detected automatically per document.
    * A per document override offers Auto, Left to Right, and Right to Left.
    * Detected and chosen directions are remembered per document.
    * Right to left page ordering works without changing logical page numbers.
    * Reading Direction is hidden when horizontal scrolling is disabled.
  * Hide Margins:
    * Hide Margins crops blank page margins without changing the file.
    * Margins are detected in the background with a cancelable progress card.
    * Detection results are cached per PDF, with per page overrides.
    * Noisy page edges are handled safely.
    * A global option always hides margins for all PDFs.
    * Applying or removing the crop keeps your reading position.
    * Hide and Show Margins are available as reader and fullscreen buttons.
    * Changing the global setting now applies to the open document as soon as settings close.
    * A manual toggle made in the reader survives unrelated settings visits.
  * Auto Scroll:
    * Auto scroll moves by real elapsed time, so speed is consistent on every device.
    * It ticks on the display's frame clock instead of a timer loop.
    * Each speed unit still means 100 pixels per second.
    * Auto scroll pauses while you interact with the document.
    * The speed is remembered per document.
    * The minimum speed is lower and speed changes are finer.
    * Auto scroll controls stay above the bottom overlays.
    * Pending speed saves flush when switching documents or closing the app.
  * Reader Controls:
    * Toolbar, shortcut bar, and fullscreen buttons are all configurable.
    * Drag and drop reordering dialogs with a reset option.
    * New actions include open online PDF, switch theme, extract text, fullscreen, and links.
    * Fullscreen shortcuts for Table of Contents and Go To Page.
    * The second toolbar is fully configurable instead of reload only.
    * The shortcut bar scrolls horizontally when it holds many actions.
    * Redesigned fullscreen buttons with outlines and slight elevation.
    * The margin buttons use the same style and size as the others.
    * The Open Online PDF action warns before opening insecure links.
  * Fullscreen:
    * True edge to edge fullscreen, including notch areas.
    * A fullscreen info card shows the time, page number, and reading percentage.
    * One Scrolling Info Card setting picks which parts appear.
    * The info card and its text are centered.
    * The PDF name is hidden from the card by default.
    * The card also appears while dragging the scroll handle, in both modes.
    * The old reading progress overlay was removed in favor of the info card.
    * In fullscreen the scroll handle stops below the status bar.
    * The document top stays reachable while dragging the handle.
  * Viewer:
    * The theme toggle switches PDF dark mode in place, without restarting.
    * The toggle shows a sun in dark mode and a moon in light mode.
    * It updates live on the toolbar, shortcut bar, and fullscreen overlay.
    * It hides when the PDF theme follows the system.
    * The brightness panel opens at the same percentage as the system slider.
    * The panel resyncs with the system on every open.
    * Double tap zoom ends at a page height fit in horizontal mode.
    * The mouse wheel scrolls the page vertically or horizontally.
    * Holding Ctrl while scrolling the wheel zooms.
    * Wheel zooming no longer snaps to a page afterward.
    * Mouse back and forward buttons turn pages, toggleable in settings.
    * Keyboard forward keys no longer accidentally turn pages.
    * A new browser like scrolling mode avoids sideways drift while reading.
    * Sideways movement unlocks only on a clearly horizontal pull.
    * An Always Open at First Page switch skips restoring the saved position.
    * Positions still save while reading, so progress and resume keep working.
    * An option shows the current page and total before the title in the toolbar.
    * Tapping the scroll handle now actually shows the go to page dialog.
    * Improved the page display on the scroll handle.
    * An option shows or hides page numbers on the scroll handle.
    * Reloading a document no longer flashes stale page content.
    * A swipe cancelled by the system no longer stalls background loading.
    * The scroll handle shows the full row range in dual page mode.
  * Text Selection:
    * Live inline text selection with larger, easier to grab handles.
    * Selection handles have a bigger touch target.
    * A floating selection card appears near the selection.
    * The card has two rows, with six color swatches on top.
    * Below them: Copy, Share, Web, Note, Translate, and Delete or Discard.
    * Selected text is fetched only when you actually use an action.
    * Copy no longer shows a snackbar, since Android already shows one.
    * The Share button renders the selected quote as a square image.
    * Quote images show book name, author, an optional made by mark, and three themes.
    * A reflow option reshapes the quoted text in the image.
    * RTL selection carets mirror with their anchor side.
    * Double tap selects whole vocalized Arabic words.
  * Highlights and Notes:
    * Highlight text and save real highlight annotations into the PDF itself.
    * Save into the file or as a copy.
    * Highlight edits draw immediately as an overlay, with no page reload.
    * Highlights that already exist in the document are detected and editable.
    * Tapping an existing highlight selects it instantly, without the double tap wait.
    * The selection box stays glued to the highlight while the page scrolls and zooms.
    * The highlight strip offers exactly six colors.
    * The customize dialog requires picking exactly six from the palette.
    * Existing users' color choices are migrated.
    * Brown replaced Gray in the palette, and Gray is reserved for notes.
    * A toast explains when a color tap is refused at the minimum or maximum.
    * Notes are stored inside the PDF on the highlight itself.
    * Notes carry creation and modification dates.
    * A note editor, viewer, and confirmed delete live on the selection card.
    * The note button switches its icon when a note exists.
    * Noted text shows an underline indication.
    * A note on plain text creates a gray highlight.
    * A new My Notes page under the reader menu.
    * Note cards show quote, note, page, chapter location, and date.
    * My Notes has search, date sorting, and plain text export.
    * A new My Highlights page with the same features plus a color filter.
    * It lists every highlight in the document.
    * Tapping an entry centers and zooms the reader on that highlight.
    * Grouped highlights focus their full shape, foreign highlights their own rectangle.
    * Opening either page with unsaved edits asks to save, discard, or cancel.
    * Unsaved highlights are recovered after a crash.
    * Unsaved changes survive rotation and app restarts.
    * Saving and discarding are two clear buttons that appear only with unsaved changes.
    * Both grey out while saving and a progress bar runs until the save finishes.
    * Highlights save off the main thread, so the interface never freezes.
    * Share quotes are capped at 400 characters, trimmed at a word boundary, with a notice when shortened.
    * RTL quotes draw upright with correct paragraph direction and mirrored marks.
  * Signatures:
    * Draw a signature and place it on any page.
    * Signatures can be moved and resized before saving.
    * Saved signatures are written into the PDF itself.
    * They are embedded as page content, so every reader displays them.
    * Signatures are stored as vector strokes, staying sharp at any zoom.
    * The drawn signature is remembered for reuse in later documents.
    * Ink colors: black, blue, red, and green.
    * Pending signatures survive rotation and app restarts.
    * Discarding a placed signature goes through one confirmed flow.
  * Forms:
    * Fill in PDF form fields.
    * Form edits no longer reload the page on every change.
    * A tap tolerance makes checkboxes easier to hit.
    * The fill in dialog shows the field's readable label instead of an internal path.
    * Fields without a label show their short name, and unnamed fields a generic title.
  * Saving and Files:
    * Updating the existing file writes directly to it when possible, with no picker.
    * Documents opened through the picker or other apps update in place when reachable.
    * The locate prompt appears only when the file truly cannot be written.
    * Files in the Downloads folder resolve to durable on disk paths.
    * The reader recovers from unreadable file links by reopening the on disk file.
    * That recovery guards against retry loops.
    * Saving highlights can no longer target the wrong document.
    * Closed the window where crop or reload could lose unsaved highlights.
    * Printing downloaded PDFs now works.
    * File identity is stable for cloud files.
    * Saving confirms with Changes saved, covering highlights, signatures, and form fillings.
    * The save options now read Update the same file and Save as a new file.
    * Saving can no longer damage the original file if the write fails, and failed saves keep every edit and offer saving a copy.
    * Interrupted saves finish in the background instead of freezing the save controls.
    * Downloaded online PDFs open right away and file into Downloads in the background.
    * Saving a link opened PDF updates its Downloads copy directly, with no picker.
    * Reopening the same link shows the saved changes.
    * Sharing a link opened PDF asks whether to share the link or the PDF file.
    * Sharing a link opens it as an online PDF, including repeated shares.
    * Temporarily shared PDFs are copied to Documents/MJ PDF, and the copy becomes their permanent location.
    * The copy happens only for provably temporary shares, with a switch in History and Privacy.
    * Reopening a dead share opens the saved copy or a library match, or offers a verified locate dialog.
    * History records remember the original share link so stale entries find their saved copy.
    * Shared files always carry the .pdf extension so other apps recognize them.
    * File Properties shows the file path when the document has a real on disk location.
  * Text Mode:
    * The old Text Mode was replaced with a dedicated Text Mode page.
    * The current page stays in sync between the PDF and Text Mode.
    * Typography settings: multiple fonts, text size, themes, and a reset action.
    * A Readable Line Length option limits the text width.
    * Reflow rebuilds real paragraphs instead of raw hard wrapped lines.
    * Headings are detected by font size and boldness, shown in three levels.
    * Hyphenated line breaks are joined correctly, including soft hyphens.
    * Bullet, numbered, and lettered lists keep their own lines.
    * Poetry, code listings, and short line pages are left unjoined.
    * Rotated text and extremely dense pages fall back to a safe joiner.
    * Arabic and right to left pages join with direction aware rules.
    * Subscripts and superscripts no longer explode math text into fragments.
    * Line detection follows horizontal continuity, not just baseline shifts.
    * Join Paragraphs and Detect Headings are separate switches.
    * They work independently, including headings on unjoined lines.
    * Turning both off restores the exact legacy output.
    * Choices are remembered per book, and new books use the defaults.
    * Reset in the typography sheet clears the book's saved choices.
    * Toggling reformats already open pages in place.
    * Text Mode is always fullscreen and rehides the bars after dialogs.
    * Bottom controls stay above the gesture area.
    * The page slider seeks through pages live while dragging.
    * Controls are constrained on large screens so they stay reachable.
    * An option opens PDFs in Text Mode by default.
    * Tapping the page slider jumps to the tapped page, and releasing a drag lands reliably.
    * Slider gestures interrupted by the system no longer jolt the page.
    * Page jumps settle exactly on the target page.
    * Vocalized Arabic text keeps its marks attached and ordered.
    * Hyphen wrapped words are rejoined and search offsets stay exact.
  * Search:
    * Results stream in while pages are scanned, instead of appearing at the end.
    * The scan keeps running after you pick a result.
    * Done cancels the active search session.
    * Previous and next arrows with a live counter navigate results inside the reader.
    * The results bar shows your position, like 3 of 47.
    * The arrows hide when the full result list is not cached.
    * Picking a result centers the view on the match.
    * A Zoom to Search Results setting also zooms in, off by default.
    * The results bar moves to the top when the match sits low on the screen.
    * Clicking the results text reopens the full list at the same position.
    * The results bar stays alive while the results page is open.
    * Picking a new result replaces the old bar cleanly.
    * Done is the only thing that clears the search highlight.
    * Search sessions are cached per document and query.
    * Reopening the same search is instant, with no spinner.
    * Cached results restore directly, without extracting text again.
    * Expanded results restore expanded with their full text.
    * Sessions with more than 5000 hits are not cached.
    * Stale cached sessions for the same query are evicted.
    * Search remembers list position, scroll offset, expanded results, and the filter query.
    * An option ignores accents when searching.
    * Search highlighting no longer stretches the highlighted text taller.
    * Fixed search highlighting the wrong words in documents with many line breaks.
    * Show more expands the right result in cached searches.
    * Filtered result lists no longer corrupt the result order.
    * Fixed a broken progress bar when opening cached results.
    * Search highlights no longer double draw in dark mode.
    * Search highlight cleanup can no longer delete real highlights.
    * Big performance improvements on the search page.
    * Closing search cleans up leftover markers.
    * A hit that cannot be highlighted shows a brief notice without ending navigation.
    * Navigating results no longer reloads the whole document per hit.
  * Table of Contents and Links:
    * Search inside the Table of Contents matches titles and page numbers.
    * Matching ancestors stay visible and matching branches expand automatically.
    * Searching never overwrites your saved expansion state.
    * Expand all and collapse all buttons.
    * Expansion, scroll position, and the query survive rotation and reopening.
    * The contents page highlights the path to the current chapter.
    * A locate button expands ancestors and scrolls to the current headline.
    * The current page is provided by both the reader and Text Mode.
    * Jumping from the contents shows a snackbar to jump back.
    * Larger expand and collapse touch targets.
    * Huge tables of contents scroll smoothly now.
    * Nested outline entries build correctly again, restoring the expand button.
    * The contents screen no longer mixes in user bookmarks.
    * The Links page search icon appears reliably after links load.
    * The links list fills in progressively.
  * Bookmarks:
    * Bookmark the current page from the reader.
    * The bookmark action shows filled or outline depending on state.
    * A dedicated Bookmarks screen lists, opens, renames, and deletes bookmarks.
    * Bookmarks can be reordered by drag.
    * Deleting a bookmark asks for confirmation.
    * Bookmarks are stored per document and survive reopening.
    * Deleting a bookmark updates the reader's toggle immediately.
    * The bookmark icon is correct right after opening a document.
    * Bookmark rows show the chapter each bookmark belongs to.
  * Navigation History:
    * Navigation history tracks jumps with back and forward.
    * History got its own full page instead of a dialog.
    * It records link, bookmark, contents, search, and go to jumps.
    * Long reading stops are added automatically.
    * Forward history appears above the current position, like a browser.
    * A highlighted current page row separates the forward and back sections.
    * Forward and back rows get distinct icons.
    * Tapping a forward entry navigates forward through the stack.
    * History opens even when only forward entries exist.
    * Revisiting a page moves it to the top instead of duplicating it.
    * History rows show the chapter path from the table of contents.
    * Chapter paths use a small arrow as the separator.
    * Jumping back after an internal link restores the exact view you left.
    * Jump Back, Jump Forward, and History grey out when unavailable.
  * Reader Menu:
    * The reader menu is now a two section tile grid.
    * Sections are labeled Actions and Pages, centered.
    * Tiles show an icon with a short label.
    * The theme tile reads Light Mode or Dark Mode dynamically.
    * Actions include Open PDF, Search, Bookmark Page, and more.
    * Pages include Table of Contents, Bookmarks, Text Mode, and Links.
    * The navigation tiles sit together in the last Actions row.
    * The menu opens faster on its first use.
    * Menu content has comfortable spacing away from the edges.
    * Action labels are shorter across the whole app.
    * The old overflow menu is gone.
  * Translation and Dictionary:
    * A Translate action translates the selected text.
    * Translation engines are picked in settings.
    * DeepL is marked unstable in the engine picker.
    * An offline dictionary can be installed from settings.
    * It is built from WordNet with senses, examples, synonyms, and irregular inflections.
    * The download is about 7 MB, verified and installed safely with progress shown.
    * Lookups normalize case, possessives, and inflections.
    * With the dictionary installed, Translate shows definitions for single words.
    * Each sense gets its own row, with a Translate instead button.
    * It falls back to the translator when no definition exists.
    * A define single words toggle controls this behavior.
    * The dictionary can be removed again from settings.
    * With no translator app installed, a dialog explains the choices and leads to picking a web translator.
  * Privacy and Incognito:
    * A strict per session incognito mode.
    * Incognito saves nothing: no history, positions, passwords, titles, or reading settings.
    * It also skips highlight recovery writes and system permission grants.
    * Reachable from the reader menu, the configurable buttons, and settings.
    * Long press the home open button to pick a file in incognito.
    * Every library item's options dialog has an Open in Incognito button.
    * Turning incognito off mid session records the document immediately.
    * Turning it on stops all further writes.
    * The mode survives rotation and process death.
    * It carries over to documents opened from within the session.
    * Incognito always opens a fresh reader instance.
    * The app bar turns dark while incognito, with an incognito icon by the title.
    * Toolbar icons turn white on the dark bar and restore afterward.
    * A new History and Privacy settings page.
    * A global Save Reading History switch stops all history writes, including Text Mode.
    * While off, status and hide actions on new files are blocked with a notice.
    * Adding a bookmark is blocked with a notice, removing one still works.
    * A Reading History screen lists every remembered document, including hidden ones.
    * It shows the last read date, page progress, and per item delete.
    * Confirm guarded clear actions for history, passwords, bookmarks, and recovery data.
    * Clearing recovery data also removes the stored signature.
    * Deleting a record now removes its bookmarks, recovery data, and save destination too.
    * Private and password documents never leave recovery copies on disk.
    * Exiting incognito no longer leaves the app stuck in the dark theme.
  * Backup and Restore:
    * Export and import settings, reading history, and app data.
    * An options dialog picks what to export.
    * Passwords are excluded unless explicitly opted in.
    * Export writes only the selected sections.
    * The result dialog reports what was exported and names the file.
    * Chosen export options persist across restarts.
    * Backups record hidden files too.
    * A Backup Folder setting picks where backups go.
    * The app creates its own subfolder inside the chosen location.
    * Exports write timestamped files straight into the folder, with no picker each time.
    * Export prompts for the folder once if unset and continues after picking.
    * A revoked or missing folder shows a notice and reopens the picker.
    * Each export keeps only the ten newest backup files.
    * An Automatic Daily Backup switch schedules a daily export.
    * First enable walks through the folder pick and time pick.
    * A Backup Time row shows the schedule and the last run status.
    * Daily backups export settings and history without passwords.
    * The schedule reanchors on every app start and repairs itself after restores.
    * Import opens the picker directly and validates the file before touching anything.
    * Settings only backups apply immediately and restart the app.
    * Backups containing history show a wipe warning with a countdown confirm.
    * Confirmed imports wipe old data and insert the backup as is.
    * Device specific settings like the backup folder are never exported or overwritten.
    * Malformed backup files report a clear invalid file message.
    * Import and export cannot be interrupted midway by navigation.
    * Every backup is verified by reading it back before it is declared successful.
    * Interrupted exports can no longer leave corrupt files, and leftovers are swept automatically.
    * Scheduled backups run even when the app is closed, with retries before reporting failure.
    * Only automatic backups are pruned, manual backups are never auto deleted.
    * Importing first writes a verified safety snapshot, restorable at any time from settings.
    * The restore is atomic, a crash mid import can no longer wipe the library.
    * Restored settings are validated, bad values self heal to defaults.
    * Failed or stalled automatic backups surface as a notice with a shortcut to backup settings.
    * Import runs in the background with progress and a one time summary of what was restored.
    * Backups use an explicit fixed format that release builds can never corrupt, and the broken release's file imports again.
    * Every export proves the file can be fully imported, and unreadable backups are rejected up front.
    * Restoring a backup no longer replays old annotations onto documents.
    * All backup errors are specific and translatable.
  * Settings:
    * Settings reorganized into searchable pages.
    * Pages: Appearance, Reading, Controls, Text, Highlighting, History and Privacy, and Advanced.
    * Every option was revised, with better titles and descriptions.
    * Obsolete options were deleted.
    * The old advanced configuration screen merged into settings.
    * A new Home Page settings section.
    * A Highlighting page with the detection switch and the color picker.
    * A Translation and Dictionary page.
    * Selection dialogs share one full width row style with ripple.
    * Removed the obsolete long press copy dialog setting.
    * The settings page was reorganized.
    * The chosen light or dark theme applies immediately on app start.
  * Design:
    * Material You dynamic colors across the whole app.
    * Consistent theming and colors everywhere.
    * A modern file properties dialog with page size, fonts, and properly parsed dates.
    * One consistent snackbar style: floating rounded cards with an outline.
    * Snackbar action buttons are underlined and colored.
    * A rewritten intro with five Material You pages.
    * The intro pages: welcome, library, reading comfort, annotation, and customization.
    * The welcome page shows the logo, the privacy pitch, and a made by link.
    * Page dots, a Skip button, and Get Started.
    * No upfront storage permission request in the intro.
    * The intro now also shows on Android 6 devices.
    * A new What's New page replaces the old dialog.
    * What's New opens once after the intro.
    * The About page was rebuilt with a logo, name, and copyable version chip.
    * A featured card links to the official MJ PDF page.
    * Grouped section cards with icon and label rows.
    * The author section links to mudlej.com.
    * License information moved to a better place.
    * The reader draws edge to edge behind a transparent navigation bar.
    * Bottom overlays lift above the Android gesture area.
    * Fullscreen hides everything cleanly again.
    * The highlight strip uses one size and uniform spacing for swatches and buttons.
    * Themed status and navigation bar colors.
    * The main layout no longer draws under the system bars.
    * The home permission card got proper top spacing.
    * Go To Page and All Pages follow the selected theme.
    * Improved accessibility support.
    * The finalized V3 logo, with a vector version on the welcome and About pages.
    * Release notes appear once after each update, with a link to the release.
  * Languages:
    * Added Italian, Dutch, French, Simplified Chinese, Persian, Polish, and Ukrainian translations.
    * Updated the existing translations.
    * Cleaned up the merged community translations.
    * All fourteen partial translations were completed, and every new text is translated.
  * Performance:
    * The PDF engine is now built for speed instead of size.
    * This fixes a years old rendering slowdown, and pages render noticeably faster.
    * Pinch zoom renders whole page snapshots instead of a tile patchwork.
    * Pages re render in steps while pinching instead of only after release.
    * One previous snapshot per page is kept, recycled oldest first.
    * The full quality pass still runs when the gesture ends.
    * Bigger default tiles, and existing custom settings are preserved.
    * The tile cache follows a fixed pixel budget with sane bounds.
    * Eviction removes the genuinely oldest tiles first.
    * Tiles draw sorted oldest to newest, so newer tiles stay on top.
    * Cache eviction pauses during pinch gestures and trims on release.
    * Auto scroll reloads tiles a few times per second instead of hundreds.
    * Search, Table of Contents, and Links open the PDF off the main thread.
    * No jank on entry for big files.
    * The properties dialog no longer copies the whole PDF to read its size.
    * Book covers render off the main thread everywhere.
    * Reloading the PDF and changing reading direction no longer block the interface.
    * A stopped render queue no longer wastes work.
    * Contents rows recycle individually instead of rebuilding the whole tree.
    * A batched native call fetches character geometry and font data in one pass.
    * Text extraction uses a bounded cache with safe page lifecycle handling.
    * Rendering is chunked and cancellable, so stale work stops in milliseconds.
    * Every page gets a render once preview, cached in memory and on disk, drawn under the tiles.
    * An idle sweep pre renders previews for the whole book, yielding instantly to interaction.
    * Preview resolution adapts to the device, with smaller previews on low memory phones.
    * Saving previews to disk no longer causes dropped frames.
    * Toggling the night theme repaints instead of re rendering everything.
  * Fixes:
    * The database moved out of the cache directory.
    * History, passwords, and progress no longer vanish when the cache is cleared.
    * Fixed a native memory leak on every rendered tile.
    * Fixed native leaks when opening PDFs from bytes and when opening fails.
    * Fixed a native leak when reading empty document metadata.
    * Fixed document leaks when a load is cancelled or fails midway.
    * Search, contents, and links pages no longer leak a document copy per visit.
    * Fixed document leaks on failure paths in the cover renderer.
    * Cached temporary copies of PDFs no longer pile up on disk.
    * The file copy helper closes its streams on all paths.
    * Fixed a crash when rendering races with closing a document.
    * Closing a document mid operation is now safe.
    * Fixed crashes when touching pages that are not open.
    * Fixed four crashes that could happen with no document loaded.
    * Fixed crashes after leaving a screen with background work still running.
    * Fixed a possible crash or hang on huge or broken tables of contents.
    * Fixed a crash when typing more than ten digits in go to page.
    * Fixed a crash on Turkish devices when using list filters.
    * Fixed a crash when swiping a record during a delete animation.
    * Fixed a launch crash on devices without a brightness setting.
    * Fixed a wrong thread crash, and a doubled message, when text extraction fails.
    * The first run dialog can no longer appear on a destroyed screen.
    * The render thread survives rare race errors instead of crashing the app.
    * Hardened the native layer against overflows, bad sizes, and missing checks.
    * Fixed the viewer staying blank after switching away and back.
    * Fixed the reading position not restoring across screen reloads.
    * Fixed the back button breaking when volume key page turning is enabled.
    * Fixed the back arrow on the About page doing nothing.
    * Fixed PDF annotations not rendering in dark theme.
    * Fixed selection handles anchoring wrong in right to left text.
    * Fixed loading, animation, and layout glitches in Text Mode.
    * An interrupted pinch no longer leaves the tile cache oversized forever.
    * Snapshot cleanup cannot recycle the wrong tile on small pages.
    * Duplicate thumbnails no longer evict live ones.
    * Fixed a thread safety hole in page coordinate mapping.
    * Fixed small state races in scanning, byte holding, and date storage.
    * Text extraction flags reset properly per document.
    * Saving auto scroll speed no longer blocks the interface.
    * Hardened document and margin detection lifecycle handling.
    * With Home disabled, back reopens the picker instead of exiting.
    * Cancelling that picker exits the app, so back twice still leaves.
    * The double tap to exit prompt still comes first when enabled.
    * The toolbar home arrow updates immediately after toggling the Home setting.
    * Fixed a memory corruption risk when closing pages of form documents.
    * A failed database relocation recovers on the next launch.
    * A stale form field indicator no longer lingers after form edits.
  * Development:
    * Updated to Android SDK 36, Gradle 9.5, Kotlin 2.4, and AGP 9.
    * Raised the minimum Android version from 5.0 to 6.0.
    * Targets Android 16.
    * Updated Activity KTX from 1.9.0 to 1.13.0.
    * Updated Fragment KTX from 1.7.0 to 1.8.9.
    * Updated Navigation from 2.7.7 to 2.9.8.
    * Updated Room from 2.6.1 to 2.8.4.
    * Updated AppCompat from 1.6.1 to 1.7.1.
    * Updated ConstraintLayout from 2.1.4 to 2.2.1.
    * Updated Material from 1.12.0 to 1.14.0.
    * Updated AndroidX Core from 1.10.0 to 1.18.0.
    * Updated Gson from 2.10.1 to 2.14.0.
    * Updated coroutines from 1.6.4 to 1.11.0.
    * Updated desugaring from 2.0.4 to 2.1.5.
    * Updated ACRA from 5.9.0 to 5.13.1.
    * Updated Swipe Refresh Layout from 1.1.0 to 1.2.0.
    * Removed jcenter.
    * Disabled Jetifier after removing the last support library dependency.
    * Replaced the attribution dependency with a local open source libraries dialog.
    * Removed the Flexbox workaround dependency.
    * Removed the unused color picker dependency and its libraries dialog entry.
    * Removed the old intro dependency along with its strings and images.
    * Removed legacy Kotlin plugin setup.
    * Removed redundant build plugin declarations, keeping one in the root.
    * Cleaned up build warnings for the new toolchain.
    * Page counters format through translatable resources with positional placeholders.
    * Dependency metadata is no longer embedded in releases.
    * Native libraries are aligned for 16 KB page sizes.
    * A setup guide documents building the app from a fresh Linux install.
    * The engine can be built fully from source with one script.
    * The dependency script can rebuild just the native glue code.
    * It fails properly on errors and missing files.
    * It verifies that every native library exists for every processor type.
    * A script builds, compresses, and fingerprints the offline dictionary reproducibly.
    * Dictionary builds are deterministic, so identical sources give identical hashes.
    * Replaced deprecated request code navigation with modern result launchers.
    * Converted the last Java files to Kotlin.
    * The two print adapters merged into one, with the same behavior.
    * The download task became a coroutine with identical error handling.
    * The database access layer is now thread safe.
    * Removed hidden system reflection for file descriptors.
    * Removed all dead code, functions, classes, and unused resources.
    * Stripped commented out code and leftover debug logging.
    * Removed four generations of old launcher icon backups.
    * The reader was rebuilt around a view model that owns all reader state.
    * Reader state survives process death through saved state.
    * A single composition root builds all reader controllers in explicit tiers.
    * The main reader screen shrank to a fraction of its former size.
    * Document loading lives in a dedicated loader with lifecycle events.
    * Printing, screenshots, brightness, zoom lock, and more each got a focused controller.
    * Text Mode split into loader, typography, and controls components.
    * Screens share common helpers for chrome, confirm dialogs, and filtering.
    * Reader files were reorganized into focused packages with history preserved.
    * Table of contents entries are no longer called bookmarks internally.
    * Database table names are pinned so internal renames cannot break data.
    * Internal names were aligned with Dual Page Mode and the note fields.
    * Signature internals were renamed from stamp terms to signature terms.
    * The intro was rewritten from scratch without the old library.
    * The intro activity is labeled with the app name.
    * The About screen wiring is compile checked instead of reflection based.
    * The file picker failure message is localized now.
    * Fixed dead settings rows caused by a subtle scoping bug.
    * Search coordination was reworked around one shared session.
    * Search preserves raw text through the pipeline with a normalized text mapping.
    * Improved logging around extractor failures.
    * Shared extractor setup across the search, contents, and links screens.
    * Removed the old strings that the redesigns made obsolete, in every language.

* 2.2.1
  * Avoid crashing because of "lateinit property actionBarMenu has not been initialized".
* 2.2.0
  * Multilingual Support: Added initial support for Arabic, Chinese, Turkish, German, Spanish, Brazilian Portuguese, Hindi, and Russian.
  * Orientation and Theme Options: Added an option to always use the app in horizontal mode.
  * Search and Highlighting Enhancements:
  * Added a button to return back to Search Results at the same position.
  * Improved search result highlighting for better visibility and accuracy.
  * Hide search icon when there are no results to maintain a cleaner interface.
  * Enabled the use of password-protected PDF files for Link, Bookmarks, TextMode, and Search functionalities.
  * Added support for using Text Mode, Search, Table of Contents, and Links with non-local PDF files.
  * Corrected the display of PDF names from an SMB server.
  * Implemented an experimental setting to show a 'reload PDF file' button in the action bar.
  * Ensured Action Bar Buttons and options that require a loaded PDF file are hidden when there is no file loaded.
  * Fix back button not working in some cases.
  * Ensured icon colors are consistent throughout the app.
  * Made background and text colors on Full Screen (FS) Buttons, Scroll handle, and seekbar consistent with the system theme.
  * Adjusted the scroll handle text color for better visibility in dark mode.
  * Enhanced the Full Screen Buttons layout to minimize padding and margins, improving usability beside PDF views.
  * Fixed brightness seekbar overlap with FS buttons list.
  * Improved the display of the reading progress text view to better match the app's current style.
  * Fix clicking on the page ScrollHandle not displaying the GoTo popup. (Android < 12 only)
  * Orientation and Theme Options: Resolved issues with the Dracula theme not persisting in Text Mode.
  * Computed file hash in the background to avoid crashes related to android.os.NetworkOnMainThreadException.
  * Addressed many crash scenarios related to null pointers and file format issues.
  * Attempted to resolve the issue with the back button not exiting the app properly.
  * Initialized pdfExtractor in the background, improving app performance.
  * Upgraded to Android SDK 34 and Gradle v8.3.2, including some slight refactoring.
  * Updated libraries and several major gradle versions.
  * Upgraded dependencies for the core libs (PdfiumAndroid & AndroidPdfViewer) to the latest versions.
  * Fixed settings to ensure proper functionality of the app post-compilation.
  * Removed all unused resources to streamline the project.
  * Refactored the creation of PdfExtractor for easier debugging and maintenance.
  * Note: Major updates to core libraries mark significant progress, resolving long-standing issues.
* 2.1.0
  * Material 3 design. 
  * Redesigned many UI parts and pages. 
  * App follows system theme by default. 
  * Simpler AutoScroll & Brightness buttons. 
  * Add labels to FullScreen Buttons. 
  * Add Zoom Lock button in FullScreen Mode. 
  * Added an option to save PDF password for protected files. 
  * Added an option to let PDF pages follow in settings. (Opt-in) 
  * Added an option to disable double tap in settings.
  * Added an option to switch to FullScreen mode automatically in settings.
  * Can zoom out less than 1x. 
  * Changed Max Zoom In to 10 instead of 5. 
  * Set Max Zoom In to 100 in Adv Config.
  * Improved Double Tap to Zoom in all scenarios.
  * Fixed: crashing when a user clicks on show more in search results when another one is expanding.
  * Fixed: the missing page fling setting in the settings.
  * Fixed: the second top bar hiding part of the page.
  * DEV: Switch to OpenJDK 11
  * DEV: Updated PDFium lib to 117.0.5921.0
  * DEV: Updated Libpng lib to 1.6.39
  * DEV: Updated FreeType lib to 2.13.0
  * DEV: added ~500 lines of scripts to fetch, build and copy all of the dependencies and native code with a single command.

* 2.0.1
  * Fixed back button not working in Bookmarks Activity.
  * Fixed displaying search results incorrectly.
  * Added the option to expand the text of a search result.
  * Added an option to switch to a dark theme (dracula theme) for the text and color in Text Mode.

* V2.0.0
  * Rebranded the app as MJ PDF with a new original icon.
  * Search has become blazingly fast.
  * You can search the the results of a search.
  * Added support for Hyperlinks.
  * Added a Table of Content page.
  * Added a page to see a list of all the links embedded in the file.
  * Added Text Mode to view the PDF as text. (configurable text size and color)
  * Added auto scrolling. (adjustable speed, both direction).
  * Added a button to lock horizontal scrolling.
  * Added a button to take a screenshot.
  * Added a second top bar with seven shortcuts. (hidden by default)
  * Added icons to all menu items in all pages.
  * Clicking on the scroll handle shows the 'Go To Page' dialog.
  * Prevent accidental back pressing by required double press to exit.
  * Decreased app's size by 27.5%. It became 5.1 Megabytes.
  * Fixed not remembering the last visited page sometimes.
  * Fixed hiding the Buttons and Scroll Handle while the user is still interacting with them.
  * Fixed not being able to reset the zoom to a page-width level by double tapping
  * Fixed few common crashes.
  * Fixed no stopping auto scrolling when the user exit the Full Screen Mode.

* V1.4.3+
  * Big increase in performance, especially for big files.
  * Removed the most common causes of crashing.
  * Decreased ram usage significantly.
  * Added 'Go To Page' option.
  * Added an option (seekbar) to adjust brightness in the Full Screen Mode
  * Search is now available for files of any
  * Better and more consistent theme across the app.
  * Changed App Bar style. (font, color, icons, title max lines)
  * Clicking on the title will show a message containing the full name of the pdf.
  * Changed scroll handler style.
  * Moved 'Print File' to the main menu, and put 'About' to the additional options.
  * Relabeled 'Additional Options' as 'More'
  * Disabled Text Mode since it's not usable yet and crashes a lot.
  * Hid page scroll handle if the pdf consists of only one page.
  * Improved Copy Page's Text functionality and UI.

* V1.4.2
    * Add an option to turn the page using volume buttons.
    * Add a button to disable copy page text pop up on long press.
    * Fix NumberFormatException when local use comma for decimal point.
  
* V1.4.1
    * A workaround to prevent app from crashing when opening huge files.
  
* V1.4.0
    * Updated the core libraries and fixed the security issue.
    * Added Search functionality. (experimental) ([see Text Mode and Search](https://gitlab.com/mudlej_android/mj_pdf_reader#text-mode-and-search))
    * Added Text mode to view PDFs like E-readers. (experimental) ([see Text Mode and Search](https://gitlab.com/mudlej_android/mj_pdf_reader#text-mode-and-search))
    * Added the ability to copy text from the PDF via a dialog.
    * Reorganized action bar's options and added Additional Options.
