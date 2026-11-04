package tv.own.owntv.core.companion

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONObject
import tv.own.owntv.core.R
import tv.own.owntv.core.model.SourceType

/**
 * Localized HTML renderer for the companion web UI. Markup, CSS and protocol identifiers remain
 * here; every sentence or label visible to the remote browser is resolved from Android resources first.
 */
internal object CompanionHtml {

    /** Day intervals the companion page offers; any 1–99 figure can still be set on the TV itself. */
    private val COMPANION_DAY_PRESETS = listOf(1, 2, 7, 14, 30)

    private const val CSS = """
      :root{
        color-scheme: dark;
        --bg:#040E0B; --panel:#12191700; --card:#1B211F; --card-2:#252B29;
        --line:#3F4945; --text:#DEE4E1; --muted:#BFC9C4;
        --accent:#8CEE2B; --accent-ink:#123A06; --danger:#FFB4AB;
      }
      @font-face{
        font-family:'Lora'; font-style:normal; font-weight:400 700;
        src:url('/lora.ttf') format('truetype'); font-display:swap;
      }
      *{box-sizing:border-box}
      body{
        margin:0; color:var(--text);
        font-family:system-ui,-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;
        background:
          radial-gradient(1200px 520px at 12% -12%, rgba(140,238,43,0.10) 0%, transparent 60%),
          radial-gradient(900px 460px at 100% 0%, rgba(140,238,43,0.06) 0%, transparent 62%),
          var(--bg);
        min-height:100vh;
      }
      h1,h2,.brand{font-family:'Lora',Georgia,'Noto Sans',system-ui,sans-serif; font-weight:700; letter-spacing:.2px}
      main{max-width:760px; margin:0 auto; padding:28px 20px 56px}
      .brandrow{display:flex; align-items:center; gap:12px; margin-bottom:22px}
      .dot{width:34px; height:34px; border-radius:10px; background:#52DBC8;
        display:grid; place-items:center; color:#003730}
      .dot svg{width:18px; height:18px}
      .brand{font-size:20px; color:var(--text)}
      .card{background:var(--card); border:1px solid var(--line); border-radius:20px;
        padding:26px 22px; box-shadow:0 24px 60px rgba(0,0,0,.45)}
      h1{margin:0 0 8px; font-size:24px}
      p{line-height:1.55; color:var(--muted); margin:0 0 16px}
      form{display:grid; gap:14px}
      label{display:grid; gap:6px; font-size:14px; color:var(--text)}
      input,select{border:1px solid var(--line); border-radius:12px; padding:13px 14px;
        background:#0C1311; color:var(--text); font-size:16px; width:100%}
      input:focus,select:focus{outline:none; border-color:var(--accent);
        box-shadow:0 0 0 3px rgba(140,238,43,.20)}
      .grid{display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px}
      .checks{display:grid; gap:10px; grid-template-columns:repeat(2,minmax(0,1fr)); margin-top:2px}
      .check{display:flex; align-items:center; gap:9px; color:var(--muted)}
      .check input{width:18px; height:18px}
      .tabs{display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; margin-bottom:18px}
      .tab{border:1px solid var(--line); border-radius:12px; padding:12px 10px; background:var(--card-2);
        color:var(--muted); font-weight:600; font-size:15px; cursor:pointer; font-family:inherit}
      .tab.active{border-color:var(--accent); background:#1E2E0C; color:#EAFFD0}
      .panel{display:none}
      .panel.active{display:grid; gap:14px}
      button.go{border:0; border-radius:12px; padding:15px 16px; background:var(--accent);
        color:var(--accent-ink); font-weight:700; font-size:16px; cursor:pointer; margin-top:4px}
      .pin{font-family:'Lora',Georgia,'Noto Sans',system-ui,sans-serif; font-size:34px; letter-spacing:12px; text-align:center;
        padding:16px; caret-color:var(--accent)}
      .err{color:var(--danger); font-size:14px; margin:0 0 12px}
      .hint{font-size:13px; color:var(--muted); margin-top:6px}
      a{color:var(--accent)}
      @media (max-width:640px){.grid,.checks,.tabs{grid-template-columns:1fr}}
    """

    private const val LOGO_SVG =
        """<svg viewBox="0 0 24 24" fill="none"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>"""

    private class Copy(private val context: Context) {
        private fun s(@StringRes id: Int): String = context.getString(id)

        val app = s(R.string.app_name)
        val pinTitle = s(R.string.companion_pin_page_title)
        val pinHeading = s(R.string.companion_pin_heading)
        val pinDescription = s(R.string.companion_pin_description)
        val pinPlaceholder = s(R.string.companion_pin_placeholder)
        val pinAria = s(R.string.companion_pin_aria)
        val continueLabel = s(R.string.companion_continue)
        val pinMismatch = s(R.string.companion_pin_mismatch)

        val addTitle = s(R.string.companion_add_page_title)
        val addHeading = s(R.string.companion_add_heading)
        val addDescription = s(R.string.companion_add_description)
        val xtream = s(R.string.setup_xtream)
        val m3u = s(R.string.setup_m3u)
        val stalker = s(R.string.setup_stalker_mac)
        val name = s(R.string.setup_source_name_optional)
        val defaultIptv = s(R.string.setup_default_iptv)
        val defaultPlaylist = s(R.string.setup_name_default_playlist)
        val defaultPortal = s(R.string.setup_default_portal)
        val serverUrl = s(R.string.setup_server_url)
        val serverExample = s(R.string.setup_server_example)
        val playlistUrl = s(R.string.setup_playlist_url_local_file)
        val playlistExample = s(R.string.setup_playlist_example)
        val playlistFile = s(R.string.companion_playlist_file)
        val playlistUrlOrFile = s(R.string.companion_playlist_url_or_file)
        val portalUrl = s(R.string.setup_portal_url)
        val portalExample = s(R.string.setup_portal_example)
        val username = s(R.string.setup_username)
        val password = s(R.string.setup_password)
        val userAgent = s(R.string.setup_user_agent_optional)
        val optional = s(R.string.settings_metadata_optional)
        val epgUrl = s(R.string.settings_epg_sources_url)
        val epgExample = s(R.string.settings_epg_sources_url_hint)
        val syncXtreamHint = s(R.string.companion_sync_xtream_hint)
        val syncStalkerHint = s(R.string.companion_sync_stalker_hint)
        val live = s(R.string.setup_live_tv)
        val movies = s(R.string.setup_movies)
        val series = s(R.string.setup_series)
        val now = s(R.string.setup_now)
        val later = s(R.string.setup_later)
        val off = s(R.string.setup_off)
        val autoRefresh = s(R.string.setup_auto_refresh)
        val refreshStartup = s(R.string.settings_sources_refresh_startup)
        val refresh6 = s(R.string.settings_sources_refresh_6h)
        val refresh12 = s(R.string.settings_sources_refresh_12h)
        // The TV form lets the user name any number of days; a web <select> cannot, so it offers the
        // common ones and the exact figure can still be changed on the TV afterwards.
        val refreshDays = COMPANION_DAY_PRESETS.associateWith {
            context.resources.getQuantityString(R.plurals.settings_sources_refresh_days, it, it)
        }
        val defaultPlaylistLabel = s(R.string.companion_default_playlist)
        val sendToTv = s(R.string.companion_send_to_tv)

        val editTitle = s(R.string.companion_edit_page_title)
        val editDescription = s(R.string.companion_edit_description)
        val keepCurrent = s(R.string.companion_keep_current)
        val passwordKeep = s(R.string.setup_password_keep)
        val savedEditAgain = s(R.string.companion_saved_edit_again)

        val savedTitle = s(R.string.companion_saved_page_title)
        val savedHeading = s(R.string.companion_saved_heading)
        val savedSendAnother = s(R.string.companion_saved_send_another)
        val savedBackupBody = s(R.string.companion_saved_backup_body)
        val savedBackupLink = s(R.string.companion_saved_backup_link)
        val savedImageBody = s(R.string.companion_saved_image_body)
        val savedImageLink = s(R.string.companion_saved_image_link)

        val backupTitle = s(R.string.companion_backup_page_title)
        val backupHeading = s(R.string.companion_backup_heading)
        val backupDescription = s(R.string.companion_backup_description)
        val backupFile = s(R.string.companion_backup_file)
        val chooseBackup = s(R.string.companion_choose_backup)
        val sending = s(R.string.companion_sending)
        val couldNotReach = s(R.string.companion_could_not_reach)
        val couldNotRead = s(R.string.companion_could_not_read)
        // Full sentence template; the browser substitutes only the numeric HTTP status after the
        // Android resource has supplied the localized sentence and punctuation.
        val uploadFailed = s(R.string.companion_upload_failed).replace("%1\$d", "__STATUS__")

        val imageTitle = s(R.string.companion_image_page_title)
        val imageHeading = s(R.string.companion_image_heading)
        val imageDescription = s(R.string.companion_image_description)
        val imageFile = s(R.string.companion_image_file)
        val chooseImage = s(R.string.companion_choose_image)
        val imageTooLarge = s(R.string.companion_image_too_large)

        val downloadTitle = s(R.string.companion_download_page_title)
        val downloadHeading = s(R.string.companion_download_heading)
        val downloadDescription = s(R.string.companion_download_description)
        val downloadBackup = s(R.string.companion_download_backup)
    }

    private fun page(context: Context, title: String, inner: String): String {
        val c = Copy(context)
        // AppLocale.wrap() resolves the effective device locale when the stored tag is empty.
        // Advertise that effective locale to browser screen readers and choose the matching writing
        // direction; never use the persisted empty tag itself as the HTML language.
        val locale = context.resources.configuration.locales[0]
            ?: java.util.Locale.getDefault()
        val lang = locale.toLanguageTag().ifBlank { "en" }
        val dir = if (context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"
        return """
            <!doctype html><html lang="${lang.h()}" dir="$dir"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${title.h()}</title><style>$CSS</style>
            </head><body><main>
              <div class="brandrow"><span class="dot">$LOGO_SVG</span><span class="brand">${c.app.h()}</span></div>
              $inner
            </main></body></html>
        """.trimIndent()
    }

    fun pinPage(context: Context, error: String?): String {
        val c = Copy(context)
        return page(context, c.pinTitle, """
            <div class="card">
              <h1>${c.pinHeading.h()}</h1>
              <p>${c.pinDescription.h()}</p>
              ${if (error != null) "<p class=\"err\">${error.h()}</p>" else ""}
              <form method="post" action="/">
                <input class="pin" name="pin" inputmode="numeric" pattern="[0-9]*" maxlength="6"
                       autofocus placeholder="${c.pinPlaceholder.h()}" aria-label="${c.pinAria.h()}" required>
                <button class="go" type="submit">${c.continueLabel.h()}</button>
              </form>
            </div>
        """.trimIndent())
    }

    fun formPage(context: Context, pin: String): String {
        val c = Copy(context)
        return page(context, c.addTitle, """
            <div class="card">
              <h1>${c.addHeading.h()}</h1>
              <p>${c.addDescription.h()}</p>
              <div class="tabs">
                <button type="button" class="tab active" data-k="xtream">${c.xtream.h()}</button>
                <button type="button" class="tab" data-k="m3u">${c.m3u.h()}</button>
                <button type="button" class="tab" data-k="stalker">${c.stalker.h()}</button>
              </div>

              <form class="panel active" data-k="xtream" method="post" action="/xtream?pin=$pin">
                <input type="hidden" name="type" value="xtream">
                <div class="grid">
                  <label>${c.name.h()} <input name="name" placeholder="${c.defaultIptv.h()}"></label>
                  ${autoRefreshSelect(c)}
                </div>
                <label>${c.serverUrl.h()} <input name="server" placeholder="${c.serverExample.h()}" required></label>
                <div class="grid">
                  <label>${c.username.h()} <input name="user" autocomplete="username" required></label>
                  <label>${c.password.h()} <input name="pass" type="password" autocomplete="current-password" required></label>
                </div>
                <label>${c.userAgent.h()} <input name="userAgent" placeholder="${c.optional.h()}"></label>
                <label>${c.epgUrl.h()} <input name="epgUrl" placeholder="${c.optional.h()}"></label>
                <p class="hint">${c.syncXtreamHint.h()}</p>
                <div class="grid">
                  ${scopeSelect(c, "syncLive", c.live)}
                  ${scopeSelect(c, "syncMovies", c.movies)}
                  ${scopeSelect(c, "syncSeries", c.series)}
                </div>
                <input type="hidden" name="isDefault" value="false">
                <label class="check"><input type="checkbox" name="isDefault" value="true"> ${c.defaultPlaylistLabel.h()}</label>
                <button class="go" type="submit">${c.sendToTv.h()}</button>
              </form>

              <form class="panel" id="m3uForm" data-k="m3u" method="post" action="/m3u?pin=$pin">
                <input type="hidden" name="type" value="m3u">
                <div class="grid">
                  <label>${c.name.h()} <input name="name" placeholder="${c.defaultPlaylist.h()}"></label>
                  ${autoRefreshSelect(c)}
                </div>
                <label>${c.playlistUrl.h()} <input id="m3uUrl" name="server" placeholder="${c.playlistExample.h()}"></label>
                <label>${c.playlistFile.h()} <input id="m3uFile" type="file" accept=".m3u,.m3u8,audio/x-mpegurl,application/vnd.apple.mpegurl,text/plain"></label>
                <label>${c.userAgent.h()} <input name="userAgent" placeholder="${c.optional.h()}"></label>
                <label>${c.epgUrl.h()} <input name="epgUrl" placeholder="${c.optional.h()}"></label>
                <input type="hidden" name="isDefault" value="false">
                <label class="check"><input type="checkbox" name="isDefault" value="true"> ${c.defaultPlaylistLabel.h()}</label>
                <button class="go" id="m3uSend" type="submit">${c.sendToTv.h()}</button>
                <p id="m3uStatus" class="hint"></p>
              </form>

              <form class="panel" data-k="stalker" method="post" action="/stalker?pin=$pin">
                <input type="hidden" name="type" value="stalker">
                <div class="grid">
                  <label>${c.name.h()} <input name="name" placeholder="${c.defaultPortal.h()}"></label>
                  ${autoRefreshSelect(c)}
                </div>
                <label>${c.portalUrl.h()} <input name="portalUrl" placeholder="${c.portalExample.h()}" required></label>
                <label>${context.getString(R.string.setup_mac_address).h()} <input name="mac" placeholder="${context.getString(R.string.setup_mac_example).h()}" required></label>
                <div class="grid">
                  <label>${context.getString(R.string.settings_username_optional).h()} <input name="user" autocomplete="username"></label>
                  <label>${context.getString(R.string.settings_password_optional).h()} <input name="pass" type="password" autocomplete="current-password"></label>
                </div>
                <h3>${context.getString(R.string.setup_stalker_advanced_identity).h()}</h3>
                <div class="grid">
                  <label>${context.getString(R.string.setup_stalker_serial_number_optional).h()} <input name="serialNumber"></label>
                  <label>${context.getString(R.string.setup_stalker_device_id_optional).h()} <input name="deviceId"></label>
                  <label>${context.getString(R.string.setup_stalker_device_id2_optional).h()} <input name="deviceId2"></label>
                  <label>${context.getString(R.string.setup_stalker_signature_optional).h()} <input name="signature"></label>
                </div>
                <label>${c.userAgent.h()} <input name="userAgent" placeholder="${c.optional.h()}"></label>
                <p class="hint">${c.syncStalkerHint.h()}</p>
                <div class="grid">
                  ${scopeSelect(c, "syncLive", c.live, "now")}
                  ${scopeSelect(c, "syncMovies", c.movies, "later")}
                  ${scopeSelect(c, "syncSeries", c.series, "later")}
                </div>
                <input type="hidden" name="isDefault" value="false">
                <label class="check"><input type="checkbox" name="isDefault" value="true"> ${c.defaultPlaylistLabel.h()}</label>
                <button class="go" type="submit">${c.sendToTv.h()}</button>
              </form>
            </div>
            <script>
              var tabs=document.querySelectorAll('.tab'), panels=document.querySelectorAll('.panel');
              tabs.forEach(function(t){t.addEventListener('click',function(){
                var k=t.getAttribute('data-k');
                tabs.forEach(function(x){x.classList.toggle('active',x===t)});
                panels.forEach(function(p){p.classList.toggle('active',p.getAttribute('data-k')===k)});
              });});
              ${m3uUploadScript(c, pin, urlRequired = true)}
            </script>
        """.trimIndent())
    }

    /**
     * The M3U form's submit handler (`m3uForm`, `m3uUrl`, `m3uFile`, `m3uSend`, `m3uStatus`). With no
     * file chosen it stays a plain form post. With a file chosen the browser reads it as text and posts
     * it as JSON, because a file input cannot travel in a normal urlencoded body and the TV has no
     * multipart parser. [urlRequired] is false on the edit page, where an empty address keeps the
     * current one.
     */
    private fun m3uUploadScript(c: Copy, pin: String, urlRequired: Boolean): String = """
              var mf=document.getElementById('m3uFile'), mu=document.getElementById('m3uUrl'),
                  mb=document.getElementById('m3uSend'), ms=document.getElementById('m3uStatus');
              document.getElementById('m3uForm').addEventListener('submit',function(ev){
                var file=mf.files&&mf.files[0];
                if(!file){
                  if($urlRequired&&!(mu.value||'').trim()){ms.textContent=${c.playlistUrlOrFile.js()};ev.preventDefault();return false;}
                  return true; // URL only — let the browser submit the form the old way.
                }
                ev.preventDefault();
                mb.disabled=true; ms.textContent=${c.sending.js()};
                var body={};
                new FormData(document.getElementById('m3uForm')).forEach(function(v,k){body[k]=v;});
                var r=new FileReader();
                r.onload=function(){
                  body.playlistFile=r.result; body.playlistFileName=file.name;
                  fetch('/m3u?pin=$pin',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
                    .then(function(res){
                      if(res.ok){document.open();res.text().then(function(t){document.write(t);document.close();});}
                      else{mb.disabled=false;ms.textContent=${c.uploadFailed.js()}.replace('__STATUS__',String(res.status));}
                    })
                    .catch(function(){mb.disabled=false;ms.textContent=${c.couldNotReach.js()};});
                };
                r.onerror=function(){mb.disabled=false;ms.textContent=${c.couldNotRead.js()};};
                r.readAsText(file);
                return false;
              });
    """.trimIndent()

    /**
     * One playlist's edit page ([CompanionMode.EDIT_SOURCE]): only its own type's fields, filled with
     * what [CompanionSourceEdit.prefill] allows. The secret fields start empty with a "keep" hint, and
     * nothing is required — an empty field keeps the TV's value. The fields match the TV's edit form.
     */
    fun editPage(context: Context, pin: String, prefill: CompanionPayload): String {
        val c = Copy(context)
        fun field(label: String, name: String, value: String = "", placeholder: String = "", attrs: String = "") =
            """<label>${label.h()} <input name="$name" value="${value.h()}" placeholder="${placeholder.h()}"$attrs></label>"""
        val secret = " type=\"password\" autocomplete=\"off\""
        val noFill = " autocomplete=\"off\""
        val path: String
        val fields: String
        when (prefill.type) {
            SourceType.M3U -> {
                path = "m3u"
                fields = """
                    ${field(c.name, "name", prefill.name, c.defaultPlaylist)}
                    <label>${c.playlistUrl.h()} <input id="m3uUrl" name="server" placeholder="${c.keepCurrent.h()}"></label>
                    <label>${c.playlistFile.h()} <input id="m3uFile" type="file" accept=".m3u,.m3u8,audio/x-mpegurl,application/vnd.apple.mpegurl,text/plain"></label>
                    ${field(c.userAgent, "userAgent", prefill.userAgent, c.optional)}
                    ${field(c.epgUrl, "epgUrl", prefill.epgUrl, c.optional)}
                """.trimIndent()
            }
            SourceType.STALKER -> {
                path = "stalker"
                fields = """
                    ${field(c.name, "name", prefill.name, c.defaultPortal)}
                    ${field(c.portalUrl, "portalUrl", prefill.portalUrl, c.portalExample)}
                    ${field(context.getString(R.string.setup_mac_address), "mac", placeholder = c.keepCurrent, attrs = secret)}
                    <div class="grid">
                      ${field(context.getString(R.string.settings_username_optional), "user", prefill.user, attrs = noFill)}
                      ${field(context.getString(R.string.settings_password_optional), "pass", placeholder = c.keepCurrent, attrs = secret)}
                    </div>
                    <h3>${context.getString(R.string.setup_stalker_advanced_identity).h()}</h3>
                    <div class="grid">
                      ${field(context.getString(R.string.setup_stalker_serial_number_optional), "serialNumber", placeholder = c.keepCurrent, attrs = secret)}
                      ${field(context.getString(R.string.setup_stalker_device_id_optional), "deviceId", placeholder = c.keepCurrent, attrs = secret)}
                      ${field(context.getString(R.string.setup_stalker_device_id2_optional), "deviceId2", placeholder = c.keepCurrent, attrs = secret)}
                      ${field(context.getString(R.string.setup_stalker_signature_optional), "signature", placeholder = c.keepCurrent, attrs = secret)}
                    </div>
                    ${field(c.userAgent, "userAgent", prefill.userAgent, c.optional)}
                """.trimIndent()
            }
            SourceType.XTREAM, SourceType.LOCAL_BACKUP -> {
                path = "xtream"
                fields = """
                    ${field(c.name, "name", prefill.name, c.defaultIptv)}
                    ${field(c.serverUrl, "server", prefill.server, c.serverExample)}
                    <div class="grid">
                      ${field(c.username, "user", prefill.user, attrs = noFill)}
                      ${field(c.passwordKeep, "pass", placeholder = c.keepCurrent, attrs = secret)}
                    </div>
                    ${field(c.userAgent, "userAgent", prefill.userAgent, c.optional)}
                    ${field(c.epgUrl, "epgUrl", prefill.epgUrl, c.optional)}
                """.trimIndent()
            }
        }
        val m3u = prefill.type == SourceType.M3U
        val formId = if (m3u) " id=\"m3uForm\"" else ""
        val sendId = if (m3u) " id=\"m3uSend\"" else ""
        val status = if (m3u) "<p id=\"m3uStatus\" class=\"hint\"></p>" else ""
        val script = if (m3u) "<script>\n${m3uUploadScript(c, pin, urlRequired = false)}\n</script>" else ""
        return page(context, c.editTitle, """
            <div class="card">
              <h1>${context.getString(R.string.companion_edit_heading, prefill.name).h()}</h1>
              <p>${c.editDescription.h()}</p>
              <form class="panel active"$formId method="post" action="/$path?pin=$pin">
                <input type="hidden" name="type" value="$path">
                $fields
                <button class="go"$sendId type="submit">${c.sendToTv.h()}</button>
                $status
              </form>
            </div>
            $script
        """.trimIndent())
    }

    /** After an edit was sent: the changes are on the TV's edit form, waiting for Save there. */
    fun editSavedPage(context: Context, prefill: CompanionPayload, pin: String): String {
        val c = Copy(context)
        val body = context.getString(R.string.companion_saved_edit_body, prefill.name)
        return page(context, c.savedTitle, """
            <div class="card">
              <h1>${c.savedHeading.h()}</h1>
              <p>${body.h()}</p>
              <p><a href="/?pin=$pin">${c.savedEditAgain.h()}</a></p>
            </div>
        """.trimIndent())
    }

    fun savedPage(context: Context, payload: CompanionPayload, pin: String): String {
        val c = Copy(context)
        val name = payload.name.ifBlank {
            when (payload.type) {
                SourceType.STALKER -> c.defaultPortal
                SourceType.M3U -> c.defaultPlaylist
                else -> c.defaultIptv
            }
        }
        val body = context.getString(R.string.companion_saved_source_body, name)
        return page(context, c.savedTitle, """
            <div class="card">
              <h1>${c.savedHeading.h()}</h1>
              <p>${body.h()}</p>
              <p><a href="/?pin=$pin">${c.savedSendAnother.h()}</a></p>
            </div>
        """.trimIndent())
    }

    fun backupUploadPage(context: Context, pin: String): String {
        val c = Copy(context)
        return page(context, c.backupTitle, uploadPage(c, pin, backup = true))
    }

    fun imageUploadPage(context: Context, pin: String): String {
        val c = Copy(context)
        return page(context, c.imageTitle, uploadPage(c, pin, backup = false))
    }

    private fun uploadPage(c: Copy, pin: String, backup: Boolean): String {
        val heading = if (backup) c.backupHeading else c.imageHeading
        val description = if (backup) c.backupDescription else c.imageDescription
        val fileLabel = if (backup) c.backupFile else c.imageFile
        val choose = if (backup) c.chooseBackup else c.chooseImage
        val endpoint = if (backup) "/backup" else "/background"
        val contentType = if (backup) "application/json" else "text/plain"
        val accept = if (backup) ".own,.json,application/json,application/octet-stream" else "image/*"
        return """
            <div class="card">
              <h1>${heading.h()}</h1>
              <p>${description.h()}</p>
              <form id="f" onsubmit="return false">
                <label>${fileLabel.h()} <input id="file" type="file" accept="$accept" required></label>
                <button class="go" id="send" type="submit">${c.sendToTv.h()}</button>
              </form>
              <p id="status" class="hint"></p>
            </div>
            <script>
              var f=document.getElementById('file'), b=document.getElementById('send'), s=document.getElementById('status');
              document.getElementById('f').addEventListener('submit',function(){
                var file=f.files&&f.files[0];
                if(!file){s.textContent=${choose.js()};return false;}
                ${if (!backup) "if(file.size>25*1024*1024){s.textContent=${c.imageTooLarge.js()};return false;}" else ""}
                b.disabled=true; s.textContent=${c.sending.js()};
                var r=new FileReader();
                r.onload=function(){
                  fetch('$endpoint?pin=$pin',{method:'POST',headers:{'Content-Type':'$contentType'},body:r.result})
                    .then(function(res){
                      if(res.ok){document.open();res.text().then(function(t){document.write(t);document.close();});}
                      else{b.disabled=false;s.textContent=${c.uploadFailed.js()}.replace('__STATUS__',String(res.status));}
                    })
                    .catch(function(){b.disabled=false;s.textContent=${c.couldNotReach.js()};});
                };
                r.onerror=function(){b.disabled=false;s.textContent=${c.couldNotRead.js()};};
                r.readAsDataURL(file); return false;
              });
            </script>
        """.trimIndent()
    }

    /**
     * One-field page: paste a TMDB API key and send it to the TV.
     *
     * The whole point is that a 32-character key is miserable to type on a remote, which is why
     * almost nobody switches to their own key. TMDB's own signup is not mobile-optimised either, so
     * the page links straight to the API settings page rather than making the user find it.
     */
    fun serviceConfigPage(context: Context, pin: String, openSubtitles: Boolean): String {
        val title = context.getString(if (openSubtitles) R.string.settings_open_subtitles_setup_title else R.string.settings_metadata_remote_advanced)
        val description = context.getString(if (openSubtitles) R.string.companion_open_subtitles_description else R.string.settings_metadata_remote_advanced_description)
        val keyLabel = context.getString(if (openSubtitles) R.string.settings_open_subtitles_api_key else R.string.settings_tmdb_api_key)
        val urlLabel = context.getString(R.string.settings_worker_server_url)
        // OpenSubtitles is an account, not just a key: the credentials are the whole point of doing
        // this from a browser instead of a remote. TMDB has no account, so it keeps the two fields.
        val credentialFields = if (!openSubtitles) "" else """
                <label>${context.getString(R.string.player_subtitles_username).h()} <input id="user" type="text" autocomplete="username" autocapitalize="off" spellcheck="false"></label>
                <label>${context.getString(R.string.player_subtitles_password).h()} <input id="pass" type="password" autocomplete="current-password"></label>
        """.trimIndent()
        // Read back into the POST body only when the fields exist; '' keeps the TMDB body unchanged.
        val credentialBody = if (!openSubtitles) "" else
            ",username:(document.getElementById('user').value||''),password:(document.getElementById('pass').value||'')"
        val send = context.getString(R.string.companion_send_to_tv)
        val sending = context.getString(R.string.companion_sending)
        val failed = context.getString(R.string.companion_upload_failed).replace("%1\$d", "__STATUS__")
        val unreachable = context.getString(R.string.companion_could_not_reach)
        return page(context, title, """
            <div class="card">
              <h1>${title.h()}</h1><p>${description.h()}</p>
              <form id="f" onsubmit="return false">
                $credentialFields
                <label>${keyLabel.h()} <input id="key" type="text" autocomplete="off" autocapitalize="off" spellcheck="false"></label>
                <label>${urlLabel.h()} <input id="url" type="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://"></label>
                <button class="go" id="send" type="submit">${send.h()}</button>
              </form><p id="status" class="hint"></p>
            </div>
            <script>
              var k=document.getElementById('key'),u=document.getElementById('url'),b=document.getElementById('send'),s=document.getElementById('status');
              document.getElementById('f').addEventListener('submit',function(){
                var body=new URLSearchParams({apiKey:(k.value||'').trim(),serverUrl:(u.value||'').trim()$credentialBody}).toString();
                b.disabled=true;s.textContent=${sending.js()};
                fetch('/serviceconfig?pin=$pin',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
                  .then(function(res){if(res.ok){return res.text().then(function(t){document.open();document.write(t);document.close();});}b.disabled=false;s.textContent=${failed.js()}.replace('__STATUS__',String(res.status));})
                  .catch(function(){b.disabled=false;s.textContent=${unreachable.js()};});return false;
              });
            </script>
        """.trimIndent())
    }

    fun serviceConfigSentPage(context: Context, pin: String): String {
        val c = Copy(context)
        return page(context, c.savedTitle, """
            <div class="card"><h1>${c.savedHeading.h()}</h1>
              <p>${context.getString(R.string.companion_service_config_sent).h()}</p>
              <p><a href="/?pin=$pin">${context.getString(R.string.companion_service_config_again).h()}</a></p>
            </div>
        """.trimIndent())
    }

    fun imageSentPage(context: Context, pin: String): String {
        val c = Copy(context)
        return page(context, c.savedTitle, """
            <div class="card"><h1>${c.savedHeading.h()}</h1>
              <p>${c.savedImageBody.h()}</p><p><a href="/?pin=$pin">${c.savedImageLink.h()}</a></p>
            </div>
        """.trimIndent())
    }

    /**
     * The only page local sync serves. There is nothing for a browser to do here — the other OwnTV
     * app is the client — but the address is discoverable and shown on screen, so someone will type
     * it in, and a blank page or a 404 would look like the feature is broken.
     */
    fun localSyncPage(context: Context): String = page(
        context,
        context.getString(R.string.local_sync_browser_title),
        """
            <div class="card"><h1>${context.getString(R.string.local_sync_browser_title).h()}</h1>
              <p>${context.getString(R.string.local_sync_browser_body).h()}</p>
            </div>
        """.trimIndent(),
    )

    fun backupSentPage(context: Context, pin: String): String {
        val c = Copy(context)
        return page(context, c.savedTitle, """
            <div class="card"><h1>${c.savedHeading.h()}</h1>
              <p>${c.savedBackupBody.h()}</p><p><a href="/?pin=$pin">${c.savedBackupLink.h()}</a></p>
            </div>
        """.trimIndent())
    }

    fun backupDownloadPage(context: Context, pin: String): String {
        val c = Copy(context)
        return page(context, c.downloadTitle, """
            <div class="card"><h1>${c.downloadHeading.h()}</h1>
              <p>${c.downloadDescription.h()}</p>
              <a class="go" href="/backup.own?pin=$pin" download="owntv-backup.own" style="display:block;text-align:center;text-decoration:none">${c.downloadBackup.h()}</a>
            </div>
        """.trimIndent())
    }

    private fun autoRefreshSelect(c: Copy): String = """
        <label>${c.autoRefresh.h()} <select name="autoRefresh">
          <option value="OFF" selected>${c.off.h()}</option>
          <option value="STARTUP">${c.refreshStartup.h()}</option>
          <option value="HOURS_6">${c.refresh6.h()}</option>
          <option value="HOURS_12">${c.refresh12.h()}</option>
          ${c.refreshDays.entries.joinToString("\n          ") { (days, label) ->
        """<option value="MANUAL:$days">${label.h()}</option>"""
    }}
        </select></label>
    """.trimIndent()

    private fun scopeSelect(c: Copy, name: String, label: String, selected: String = "now"): String {
        fun option(value: String, text: String) =
            """<option value="$value"${if (value == selected) " selected" else ""}>${text.h()}</option>"""
        return """
            <label>${label.h()} <select name="$name">
              ${option("now", c.now)}${option("later", c.later)}${option("off", c.off)}
            </select></label>
        """.trimIndent()
    }

    private fun s(@StringRes id: Int, context: Context): String = context.getString(id)

    private fun String.js(): String = JSONObject.quote(this)

    private fun String.h(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
