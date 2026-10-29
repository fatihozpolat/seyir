-- Seyir oynatıcı betiği (uosc'nin üstüne Seyir'e özel işler).
--
--  * Kalite menüsü: HLS ana listesindeki katmanlar. mpv (ffmpeg'in hls demuxer'ı) katmanları ayrı video izi
--    olarak değil "edition" (sürüm) olarak sunar; menü bunları "1080p / 720p ..." diye listeler ve seçileni
--    aynı konumdan, dosyayı kapatmadan değiştirir. Katmanlar video izi olarak gelirse (başka bir mpv
--    sürümü ya da kaynak) vid değiştirilir. Tek katmanlı yayında düğme gizlenir.
--  * Sonraki bölüm: Seyir `script-message-to seyir seyir-next-info <başlık>` ile varlığını bildirir; düğmeye
--    basılınca `script-message seyir-next` yayınlanır (Seyir IPC'den `client-message` olarak alır).
--  * Önceki bölüm: aynı biçimde `seyir-prev-info <başlık>` ve `seyir-prev`.
--  * Aynı pencerede yeni dosya (`loadfile ... replace`): `start-file` olayında bölüm başlıkları ve kalite seçimi sıfırlanır.
--  * Seyir ana menüsü (sağ tık / menü düğmesi): altyazı, ses, kalite, sonraki bölüm, hız, tam ekran.
--
-- Dil: `--script-opts=seyir-lang=tr|en` (Seyir'in arayüz dili).

local mp = require 'mp'
local utils = require 'mp.utils'
local options = require 'mp.options'

local opts = { lang = 'tr' }
options.read_options(opts, 'seyir')

local TEXT = {
	tr = {
		menu = 'Menü',
		subtitles = 'Altyazılar',
		audio = 'Ses',
		quality = 'Kalite',
		next = 'Sonraki bölüm',
		previous = 'Önceki bölüm',
		speed = 'Oynatma hızı',
		fullscreen = 'Tam ekran',
		auto = 'Otomatik',
		auto_hint = 'Pencere boyutuna göre',
		no_quality = 'Bu yayında kalite seçeneği yok',
		quality_changed = 'Kalite: %s',
		loading_next = 'Sonraki bölüm yükleniyor…',
		loading_previous = 'Önceki bölüm yükleniyor…',
		normal = 'Normal',
	},
	en = {
		menu = 'Menu',
		subtitles = 'Subtitles',
		audio = 'Audio',
		quality = 'Quality',
		next = 'Next episode',
		previous = 'Previous episode',
		speed = 'Playback speed',
		fullscreen = 'Fullscreen',
		auto = 'Auto',
		auto_hint = 'Based on window size',
		no_quality = 'No quality options for this stream',
		quality_changed = 'Quality: %s',
		loading_next = 'Loading next episode…',
		loading_previous = 'Loading previous episode…',
		normal = 'Normal',
	},
}
local T = TEXT[opts.lang] or TEXT.tr

-- Seyir ile iletişim (bkz. SeyirScript.kt)
local MSG_NEXT = 'seyir-next'
local MSG_PREV = 'seyir-prev'

local next_title = nil -- nil: sonraki bölüm yok
local previous_title = nil -- nil: önceki bölüm yok
local auto_quality = false -- kullanıcı "Otomatik"i seçti

---------------------------------------------------------------------------------------------------
-- Yardımcılar

local function set_button(name, data)
	mp.commandv('script-message-to', 'uosc', 'set-button', name, utils.format_json(data))
end

local function open_menu(menu)
	mp.commandv('script-message-to', 'uosc', 'open-menu', utils.format_json(menu))
end

local function format_bitrate(kbps)
	if not kbps or kbps <= 0 then return nil end
	if kbps >= 1000 then return string.format('%.1f Mbps', kbps / 1000) end
	return string.format('%d kbps', math.floor(kbps + 0.5))
end

---------------------------------------------------------------------------------------------------
-- Kalite katmanları

--- edition başlığından ("Bitrate: 2.149 Mbps (h264 [High] 1280x720 60 fps 2149 kbps) (aac ...)") katman bilgisi çıkarır.
--- Görüntü çözünürlüğü olmayan sürümler (yalnız ses, Matroska sürümleri) katman sayılmaz.
local function parse_edition(edition)
	local title = edition.title or ''
	local width, height = title:match('%f[%d](%d+)x(%d+)%f[%D]')
	if not height then return nil end
	local number, unit = title:match('Bitrate:%s*([%d%.]+)%s*([MKk])bps')
	local kbps = 0
	if number then
		kbps = tonumber(number) or 0
		if unit == 'M' then kbps = kbps * 1000 end
	end
	return {
		id = edition.id,
		width = tonumber(width),
		height = tonumber(height),
		fps = tonumber(title:match('([%d%.]+)%s*fps')),
		kbps = kbps,
	}
end

--- Aynı yükseklikteki katmanlardan (ses kodeği ya da bit hızı farkı) en yüksek bit hızlısını tutar; yüksekten alçağa sıralar.
local function group_by_height(layers)
	local best = {}
	for _, layer in ipairs(layers) do
		local current = best[layer.height]
		if not current or layer.kbps > current.kbps then best[layer.height] = layer end
	end
	local groups = {}
	for _, layer in pairs(best) do groups[#groups + 1] = layer end
	table.sort(groups, function(a, b) return a.height > b.height end)
	for _, group in ipairs(groups) do
		local label = string.format('%dp', group.height)
		if group.fps and group.fps >= 49 then label = label .. string.format('%d', math.floor(group.fps + 0.5)) end
		group.label = label
	end
	return groups
end

--- Geçerli yayının katmanları: {mode = 'edition'|'track', groups = {...}, current_height = n}. Katman yoksa nil.
local function collect_qualities()
	-- 1) HLS katmanları: edition listesi
	local editions = mp.get_property_native('edition-list') or {}
	if #editions > 1 then
		local layers = {}
		local current_id = mp.get_property_number('current-edition')
		local current_height
		for _, edition in ipairs(editions) do
			local layer = parse_edition(edition)
			if layer then
				layers[#layers + 1] = layer
				if layer.id == current_id then current_height = layer.height end
			end
		end
		local groups = group_by_height(layers)
		if #groups > 1 then
			return { mode = 'edition', groups = groups, current_height = current_height }
		end
	end

	-- 2) Katmanlar ayrı video izleri olarak gelirse
	local tracks = mp.get_property_native('track-list') or {}
	local layers = {}
	local current_height
	for _, track in ipairs(tracks) do
		if track.type == 'video' and not track.image and not track.albumart and track['demux-h'] then
			local layer = {
				id = track.id,
				width = track['demux-w'],
				height = track['demux-h'],
				fps = track['demux-fps'],
				kbps = (track['demux-bitrate'] or 0) / 1000,
			}
			layers[#layers + 1] = layer
			if track.selected then current_height = layer.height end
		end
	end
	local groups = group_by_height(layers)
	if #groups > 1 then
		return { mode = 'track', groups = groups, current_height = current_height }
	end
	return nil
end

--- "Otomatik": pencere yüksekliğini karşılayan en düşük katman; hiçbiri yetmiyorsa en yükseği.
local function pick_auto(groups)
	local target = mp.get_property_number('osd-height', 0)
	local choice = groups[1]
	for _, group in ipairs(groups) do
		if group.height >= target then choice = group end
	end
	return choice
end

local function switch_quality(qualities, group)
	local position = mp.get_property_number('time-pos')
	if qualities.mode == 'track' then
		mp.set_property_number('vid', group.id)
		return
	end

	-- Sürüm (edition) değişimi oynatmayı yeniden başlatır ve --start konumuna döner; konumu koru.
	local previous_start = mp.get_property('start')
	if position then mp.set_property('start', string.format('%.3f', position)) end
	local restored = false
	local function restore()
		if restored then return end
		restored = true
		mp.unregister_event(restore)
		if previous_start then mp.set_property('start', previous_start) end
		-- --start uygulanmadıysa (eski mpv) elle geri sar
		local now = mp.get_property_number('time-pos')
		if position and now and math.abs(now - position) > 3 then
			mp.commandv('seek', string.format('%.3f', position), 'absolute+exact')
		end
	end
	mp.register_event('playback-restart', restore)
	mp.add_timeout(15, restore)
	mp.set_property_number('edition', group.id)
end

local function select_quality(which)
	local qualities = collect_qualities()
	if not qualities then
		mp.osd_message(T.no_quality, 2)
		return
	end
	local group
	if which == 'auto' then
		auto_quality = true
		group = pick_auto(qualities.groups)
	else
		auto_quality = false
		local height = tonumber(which)
		for _, candidate in ipairs(qualities.groups) do
			if candidate.height == height then group = candidate end
		end
	end
	if not group or group.height == qualities.current_height then return end
	mp.osd_message(string.format(T.quality_changed, group.label), 2)
	switch_quality(qualities, group)
end

local function quality_menu_items(qualities)
	local items = {
		{
			title = T.auto,
			hint = T.auto_hint,
			value = { 'script-message-to', 'seyir', 'quality-select', 'auto' },
			active = auto_quality,
		},
	}
	for _, group in ipairs(qualities.groups) do
		items[#items + 1] = {
			title = group.label,
			hint = format_bitrate(group.kbps),
			value = { 'script-message-to', 'seyir', 'quality-select', tostring(group.height) },
			active = (not auto_quality) and group.height == qualities.current_height,
		}
	end
	return items
end

local function open_quality_menu()
	local qualities = collect_qualities()
	if not qualities then
		mp.osd_message(T.no_quality, 2)
		return
	end
	local items = quality_menu_items(qualities)
	local selected = 1
	for index, item in ipairs(items) do
		if item.active then selected = index end
	end
	open_menu({ type = 'seyir_quality', title = T.quality, items = items, selected_index = selected })
end

---------------------------------------------------------------------------------------------------
-- Sonraki bölüm

local function request_next()
	if not next_title then return end
	mp.osd_message(T.loading_next, 4)
	mp.commandv('script-message', MSG_NEXT)
end

local function request_previous()
	if not previous_title then return end
	mp.osd_message(T.loading_previous, 4)
	mp.commandv('script-message', MSG_PREV)
end

---------------------------------------------------------------------------------------------------
-- Düğmeler ve menü

local function refresh_buttons()
	-- Tanılama: Seyir'in bildirdiği başlıklar IPC'den `user-data/seyir/...` olarak okunabilir (testler kullanır)
	mp.set_property_native('user-data/seyir/next-title', next_title or '')
	mp.set_property_native('user-data/seyir/previous-title', previous_title or '')
	set_button('seyir_prev', {
		icon = 'skip_previous',
		tooltip = T.previous .. (previous_title and (': ' .. previous_title) or ''),
		command = { 'script-message-to', 'seyir', 'previous' },
		hide = previous_title == nil,
	})
	set_button('seyir_next', {
		icon = 'skip_next',
		tooltip = T.next .. (next_title and (': ' .. next_title) or ''),
		command = { 'script-message-to', 'seyir', 'next' },
		hide = next_title == nil,
	})
	set_button('seyir_quality', {
		icon = 'hd',
		tooltip = T.quality,
		command = { 'script-message-to', 'seyir', 'quality-menu' },
		hide = collect_qualities() == nil,
	})
	set_button('seyir_menu', {
		icon = 'menu',
		tooltip = T.menu,
		command = { 'script-message-to', 'seyir', 'menu' },
	})
end

local function open_main_menu()
	local items = {}
	if next_title then
		items[#items + 1] = {
			title = T.next,
			hint = next_title,
			icon = 'skip_next',
			value = { 'script-message-to', 'seyir', 'next' },
			separator = previous_title == nil,
		}
	end
	if previous_title then
		items[#items + 1] = {
			title = T.previous,
			hint = previous_title,
			icon = 'skip_previous',
			value = { 'script-message-to', 'seyir', 'previous' },
			separator = true,
		}
	end
	items[#items + 1] = { title = T.subtitles, hint = 'S', icon = 'subtitles', value = { 'script-binding', 'uosc/subtitles' } }
	items[#items + 1] = { title = T.audio, hint = 'A', icon = 'audiotrack', value = { 'script-binding', 'uosc/audio' } }
	if collect_qualities() then
		items[#items + 1] = { title = T.quality, hint = 'V', icon = 'hd', value = { 'script-message-to', 'seyir', 'quality-menu' } }
	end

	local speed = mp.get_property_number('speed', 1)
	local speed_items = {}
	for _, value in ipairs({ 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2 }) do
		speed_items[#speed_items + 1] = {
			title = value == 1 and T.normal or (tostring(value) .. 'x'),
			value = { 'set', 'speed', tostring(value) },
			active = math.abs(speed - value) < 0.001,
		}
	end
	items[#items + 1] = { title = T.speed, icon = 'speed', items = speed_items }
	items[#items + 1] = { title = T.fullscreen, hint = 'F', icon = 'fullscreen', value = { 'cycle', 'fullscreen' } }
	open_menu({ type = 'seyir_menu', items = items })
end

---------------------------------------------------------------------------------------------------
-- Kayıtlar

mp.register_script_message('seyir-next-info', function(title)
	next_title = (title and title ~= '') and title or nil
	refresh_buttons()
end)
mp.register_script_message('seyir-prev-info', function(title)
	previous_title = (title and title ~= '') and title or nil
	refresh_buttons()
end)
mp.register_script_message('next', request_next)
mp.register_script_message('previous', request_previous)
mp.register_script_message('menu', open_main_menu)
mp.register_script_message('quality-menu', open_quality_menu)
mp.register_script_message('quality-select', select_quality)

mp.add_key_binding(nil, 'next', request_next)
mp.add_key_binding(nil, 'previous', request_previous)
mp.add_key_binding(nil, 'menu', open_main_menu)
mp.add_key_binding(nil, 'quality', open_quality_menu)

-- Aynı mpv penceresine yeni dosya yüklenince (bölümden bölüme geçiş, mpv süreci yeniden kullanılır) önceki
-- dosyadan kalan durum temizlenir: sonraki/önceki bölüm başlıkları ve "Otomatik kalite" seçimi. Seyir yeni
-- dosya yüklendikten sonra kendi bilgilerini yeniden gönderir.
mp.register_event('start-file', function()
	next_title = nil
	previous_title = nil
	auto_quality = false
	refresh_buttons()
end)

-- uosc bu betikten sonra yüklenebilir (dosya adı sırası); düğmeler dosya yüklenince ve kısa gecikmelerle yinelenir
mp.register_event('file-loaded', function()
	refresh_buttons()
	mp.add_timeout(0.5, refresh_buttons)
	mp.add_timeout(2, refresh_buttons)
end)
mp.observe_property('edition-list', 'native', function() refresh_buttons() end)
mp.observe_property('track-list', 'native', function() refresh_buttons() end)
