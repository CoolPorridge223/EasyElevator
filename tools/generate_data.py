"""Generate the data side of EasyElevator's resources.

Run with Python 3, no dependencies:

    python tools/generate_data.py

Owns blockstates, translations, recipes, loot tables and the (silent) sound hooks.
Models and textures are owned by ``tools/generate_art.py``; the two scripts split the
resource tree so that neither one has to know how the other writes its files.

The blockstate of the landing door is the one place where the two halves meet: it names
every door frame model, so it lives here but is checked against the model files on disk
(``check_blockstates``) - a renamed or deleted model fails immediately instead of showing
up as a missing-texture cube in game.
"""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
ASSETS = ROOT / 'assets/easyelevator'

#: Door frame metrics, in 1/16 block.  Must match block/LandingDoorGeometry.java and the
#: constants in tools/generate_art.py: the blockstate picks a model per (column, level),
#: and the models are authored for facing=north (the blockstate rotates them).
FRAME = 3
DOOR_WIDTH = 48
SEAM = 1
LEAF_TRAVEL = (DOOR_WIDTH - 2 * FRAME - SEAM) / 2
LEAF_INNER_LEFT = FRAME + LEAF_TRAVEL               # left leaf trailing edge, whole-door units
LEAF_INNER_RIGHT = DOOR_WIDTH - FRAME - LEAF_TRAVEL  # right leaf leading edge

#: Audio file per sound event.  Empty means "declared but silent": the event exists so that
#: a resource pack (or the runtime pack described below) can fill it in later, and vanilla
#: tolerates the missing file.  ``elevator_arrival_custom`` is fed by ``client/DoorSoundPack``
#: after a player uploads an .ogg in the per-door settings panel (each slot gets its own
#: ``elevator_arrival_custom_<slot>`` event, which the runtime pack declares).
#:
#: There is deliberately **no** door open/close sound any more: the elevator's only audible
#: door-related cue is the arrival chime, and that is the one the per-door panel configures.
SOUND_FILES = {
    'elevator_arrival': ['easyelevator:man'],
}

#: model name per (level, column).  Level 0 adds the plinth and the sill, level 1 is the
#: plain jamb (and an empty model across the opening), level 2 is the lintel.
FRAME_MODELS = {
    (0, 0): 'landing_door_frame_left_bottom',
    (0, 1): 'landing_door_frame_middle_bottom',
    (0, 2): 'landing_door_frame_right_bottom',
    (1, 0): 'landing_door_frame_left',
    (1, 1): 'landing_door_frame_middle',
    (1, 2): 'landing_door_frame_right',
    (2, 0): 'landing_door_frame_top_left',
    (2, 1): 'landing_door_frame_top',
    (2, 2): 'landing_door_frame_top_right',
}


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def door_blockstate():
    """Build the landing door blockstate: facing x column x level x open -> frame model."""
    variants = {}
    for facing, rotation in [('north', 0), ('east', 90), ('south', 180), ('west', 270)]:
        for level in range(3):
            for column in range(3):
                model = FRAME_MODELS[(level, column)]
                # `open` still has to exist (the interlock is synced through it) but both
                # values point at the same model: the leaves move, the frame never changes.
                for opened in (False, True):
                    variants[f'facing={facing},column={column},level={level},open={str(opened).lower()}'] = {
                        'model': f'easyelevator:block/{model}', 'y': rotation}
    return {'variants': variants}


def check_blockstates(blockstates):
    """Fail if a blockstate names a model that tools/generate_art.py did not write."""
    for name, spec in blockstates.items():
        ids = {v['model'] for v in spec['variants'].values()}
        for model_id in sorted(ids):
            assert model_id.startswith('easyelevator:block/'), f'{name}: unexpected model {model_id}'
            path = ASSETS / 'models/block' / (model_id.split('/', 1)[1] + '.json')
            assert path.exists(), f'{name} references {model_id}, but {path} does not exist'


def item_model(name):
    """Block item models simply parent the block model they place."""
    return {'parent': f'easyelevator:block/{name}'}


def ingredient(name):
    """Build a recipe ingredient from a short name.

    A bare name is read as vanilla (``iron_ingot`` -> ``minecraft:iron_ingot``); a name that
    already carries a namespace is used verbatim, which is what the high-speed cabin's
    "upgrade an existing cabin" recipe needs (``easyelevator:cabin``).
    """
    return {'item': name if ':' in name else f'minecraft:{name}'}


def loot_block(name, **conditions):
    entry = {'type': 'minecraft:item', 'name': f'easyelevator:{name}'}
    conds = [{'condition': 'minecraft:survives_explosion'}] + list(conditions.values())
    return {'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [entry], 'conditions': conds}]}


def main():
    # --- blockstates ---------------------------------------------------------------
    blockstates = {
        'elevator_rail': {'variants': {
            f'facing={facing}': {'model': 'easyelevator:block/elevator_rail', 'y': rotation}
            for facing, rotation in [('north', 0), ('east', 90), ('south', 180), ('west', 270)]}},
        'call_button': door_blockstate(),
    }
    check_blockstates(blockstates)
    for name, spec in blockstates.items():
        write(ASSETS / f'blockstates/{name}.json', spec)

    # The landing door keeps its old registration id (easyelevator:call_button) so that
    # existing saves and stacks survive; only its item model name changed with the art.
    write(ASSETS / 'models/item/elevator_rail.json', item_model('elevator_rail'))
    write(ASSETS / 'models/item/call_button.json', item_model('call_button'))

    # --- loot tables --------------------------------------------------------------
    write(ROOT / 'data/easyelevator/loot_table/blocks/elevator_rail.json', loot_block('elevator_rail'))
    # One door item per completed door: only the bottom centre block drops.
    write(ROOT / 'data/easyelevator/loot_table/blocks/call_button.json', loot_block('call_button', **{
        'root_only': {'condition': 'minecraft:block_state_property', 'block': 'easyelevator:call_button',
                      'properties': {'column': '1', 'level': '0'}}}))

    # --- recipes ------------------------------------------------------------------
    # ``key`` order is the JSON key order; keep it stable so re-running this script is a no-op
    # on a clean tree.  Every recipe here must match the file already shipped in the repo -
    # if it does not, the script would silently change a recipe the next time somebody runs it.
    recipes = {
        'elevator_rail': (['I I', 'IRI', 'I I'], {'I': 'iron_ingot', 'R': 'redstone'}, 8),
        'call_button': ([' B ', 'IRI', '   '], {'B': 'stone_button', 'I': 'iron_ingot', 'R': 'redstone'}, 2),
        'cabin': (['III', 'IRI', 'IPI'], {'I': 'iron_ingot', 'R': 'redstone', 'P': 'piston'}, 1),
        # High-speed cabin: a field upgrade of the plain cabin - eight redstone blocks around
        # one cabin item.  (An older revision of this script wrote a standalone gold-core
        # recipe instead; the shipped resource file has been the upgrade recipe for several
        # releases, so the script now reproduces it rather than overwriting it.)
        'high_speed_cabin': (['RRR', 'RCR', 'RRR'],
                             {'C': 'easyelevator:cabin', 'R': 'redstone_block'}, 1),
        # Observation cabin: the two top corners become glass, matching the glazed shell.
        'observation_cabin': (['IGI', 'IRI', 'IPI'], {'I': 'iron_ingot', 'G': 'glass', 'R': 'redstone', 'P': 'piston'}, 1),
        # Powerful cabin (2.2.1): a heavier car - the bottom corners are iron *blocks* and the
        # drive core is a redstone block, so the recipe reads as "the load-bearing model".
        'powerful_cabin': (['III', 'IRI', 'TPT'],
                           {'I': 'iron_ingot', 'T': 'iron_block', 'R': 'redstone_block', 'P': 'piston'}, 1),
    }
    for name, (pattern, keys, count) in recipes.items():
        write(ROOT / f'data/easyelevator/recipe/{name}.json',
              {'type': 'minecraft:crafting_shaped', 'category': 'redstone', 'pattern': pattern,
               'key': {k: ingredient(v) for k, v in keys.items()},
               'result': {'id': f'easyelevator:{name}', 'count': count}})
    write(ROOT / 'data/minecraft/tags/block/mineable/pickaxe.json',
          {'replace': False, 'values': ['easyelevator:elevator_rail', 'easyelevator:call_button']})

    # --- sounds -------------------------------------------------------------------
    # Every door sound id the mod can play is declared here.  The `door_*_custom` pair has
    # no audio file in the jar on purpose: the runtime resource pack written by
    # client/DoorSoundPack supplies it once a player uploads an .ogg through the per-door
    # settings panel.  Vanilla SoundManager silently skips a declared sound whose file is
    # missing (SoundManager.isSoundResourcePresent -> skip), so the "no file uploaded yet"
    # state is silent and crash-free.  Keep these ids in sync with logic/DoorSounds.java.
    write(ASSETS / 'sounds.json',
          {key: {'subtitle': f'subtitles.easyelevator.{key}',
                 'sounds': SOUND_FILES.get(key, [])}
           for key in ['elevator_running', 'elevator_arrival', 'elevator_arrival_custom']})

    # --- translations -------------------------------------------------------------
    # 顺序即 JSON 的键顺序（en 是按下标 zip 上来的），因此新增键时两边必须同时加在同一位置。
    zh = {
        # 重载轿厢的货舱：物品提示、容器界面标题/统计/规则、两种失败提示。
        # 数字口径由 CargoLoad 的常量决定（1728 = 27×64、128 件/人、288 件/箱），改常量时这几句要一起改。
        'item.easyelevator.cargo_hint': '在轿厢内潜行右键打开 27 格货舱。',
        'screen.easyelevator.cargo': '重载电梯 · 货舱',
        'screen.easyelevator.cargo_count': '货物：%s / 1728 件',
        'screen.easyelevator.cargo_capacity': '当前限载：%s / 20 人',
        'screen.easyelevator.cargo_rule': '27 格货舱。每 128 件占 1 人载重，不足一档也计入；每 288 件显示一个货箱。按实际件数计，容器内物品不另计。装卸时保持开门，关闭面板后恢复运行。',
        'message.easyelevator.cargo_stopped': '当前状态无法装卸货物。',
        'message.easyelevator.cargo_space': '请让乘客或动物离开轿厢后部货箱区域后继续装货。',
        'itemGroup.easyelevator': '简易电梯', 'block.easyelevator.elevator_rail': '电梯轨道',
        'block.easyelevator.call_button': '电梯门', 'item.easyelevator.cabin': '电梯轿厢', 'entity.easyelevator.cabin': '电梯轿厢',
        'item.easyelevator.high_speed_cabin': '高速电梯轿厢', 'entity.easyelevator.high_speed_cabin': '高速电梯轿厢',
        'item.easyelevator.observation_cabin': '观光电梯轿厢', 'entity.easyelevator.observation_cabin': '观光电梯轿厢',
        'item.easyelevator.powerful_cabin': '重载电梯轿厢', 'entity.easyelevator.powerful_cabin': '重载电梯轿厢',
        'message.easyelevator.no_cabin': '这条线路没有轿厢，请先在轨道上放置轿厢。',
        'message.easyelevator.multiple_cabins': '这条线路存在多个轿厢，请移除多余轿厢。',
        'message.easyelevator.called': '呼叫已加入队列。', 'message.easyelevator.invalid_stop': '站点或线路已变化，或请求队列已满，请检查轨道和电梯门。',
        'message.easyelevator.existing_cabin': '这条线路已有轿厢。', 'message.easyelevator.obstructed': '轿厢需要 3×3×3 的空位，且所在区块必须已加载。',
        'message.easyelevator.enter': '请进入轿厢后右键选站；空手潜行右键可回收空轿厢。', 'message.easyelevator.selected': '目的站已加入队列。',
        'message.easyelevator.door_no_station': '开门键只在轿厢停在某一层时有效。',
        'message.easyelevator.door_close_locked': '当前不能关门：门已经关好，或轿厢正在运行。',
        # 厅外呼叫面板（右键楼层门弹出：上 / 下 / 关闭三个按钮竖排）与基准层编号
        'screen.easyelevator.hall_title': '电梯呼叫', 'screen.easyelevator.hall_up': '上行呼叫', 'screen.easyelevator.hall_down': '下行呼叫',
        'screen.easyelevator.hall_station': '站点高度 Y = %s', 'screen.easyelevator.close': '关闭',
        'message.easyelevator.hall_queued': '已登记%s呼叫。',
        'message.easyelevator.floor_base_set': '已把这一站设为 1 层，其它楼层已按它重新编号。',
        'message.easyelevator.floor_base_cleared': '已取消基准层，楼层编号回到"最低层为 1 层"。',
        'screen.easyelevator.title': '电梯选站', 'screen.easyelevator.station': '站点 %s  ·  Y = %s',
        'screen.easyelevator.status': '高度 %s  |  %s', 'screen.easyelevator.count': '%s 个站点  ·  第 %s / %s 页',
        'screen.easyelevator.empty': '同一线路尚未安装完整电梯门',
        'screen.easyelevator.open_door': '开门', 'screen.easyelevator.close_door': '关门',
        'screen.easyelevator.prev_page': '上一页', 'screen.easyelevator.next_page': '下一页',
        'phase.easyelevator.open': '开门停靠', 'phase.easyelevator.closing': '正在关门', 'phase.easyelevator.moving': '运行中',
        'phase.easyelevator.opening': '正在开门', 'phase.easyelevator.blocked': '暂停：请检查轨道或障碍',
        # 超载：强力型号超过限载人数时，相位变成 OVERLOAD（门保持全开），面板与门框都显示这两个字
        'phase.easyelevator.overload': '超载',
        # 强力型号后壁载重铭牌上的那一行（%s = 限载人数，取自 ElevatorParameters.HIGH_PASSENGER_NUM_LIMIT）
        'text.easyelevator.capacity': '限载 %s 人',
        'status.easyelevator.up': '电梯上行', 'status.easyelevator.down': '电梯下行', 'status.easyelevator.idle': '停靠',
        'subtitles.easyelevator.elevator_running': '电梯运行', 'subtitles.easyelevator.elevator_arrival': '电梯到站',
        'subtitles.easyelevator.elevator_arrival_custom': '电梯到站（自定义音效）',
        # 每扇门自己的到站音效设置面板（潜行右键楼层门打开；普通右键的厅外呼叫面板不受影响）
        'screen.easyelevator.door_sound_title': '电梯门设置', 'screen.easyelevator.door_sound_station': '站点高度 Y = %s',
        'screen.easyelevator.door_sound_station_floor': '站点高度 Y = %s  ·  第 %s 层',
        'screen.easyelevator.door_sound_row': '到站音效',
        'screen.easyelevator.door_sound_drop': '把 .ogg 拖进窗口，或把路径粘到下面（≤512 KiB）',
        'screen.easyelevator.door_sound_path': '音频文件完整路径',
        'screen.easyelevator.door_sound_upload': '上传',
        'screen.easyelevator.toggle_on': '%s 开', 'screen.easyelevator.toggle_off': '%s 关',
        'screen.easyelevator.sound_prev': '<', 'screen.easyelevator.sound_next': '>',
        'screen.easyelevator.sound_pick': '选择文件…', 'screen.easyelevator.sound_try': '试听',
        'screen.easyelevator.sound_choice_default': '默认音效',
        'screen.easyelevator.sound_off_value': '%s（已关闭）', 'screen.easyelevator.sound_flash': '▶ %s',
        'screen.easyelevator.floor_base': '设为基准层', 'screen.easyelevator.floor_base_on': '基准层（1 层）',
        'screen.easyelevator.sound_id': '音效 ID：%s', 'screen.easyelevator.sound_custom_id': '音频文件：%s',
        'message.easyelevator.door_sound_uploaded': '已上传%s，全服同步中。',
        'message.easyelevator.door_sound_upload_failed': '上传失败：请选择 512 KiB 以内的 .ogg 文件。',
        'message.easyelevator.door_sound_no_dialog': '这个游戏进程弹不出文件对话框，请检查启动器参数（不要禁用系统窗口）后重试。',
    }
    en = dict(zip(zh, [
        # 与上面的 zh 逐位对应；开头七条是货舱。
        'Sneak-use inside the cabin to open its 27-slot cargo hold.',
        'Heavy Elevator Cargo',
        'Cargo: %s / 1728 items',
        'Passengers: %s / 20',
        '27 slots. Each started batch of 128 items replaces one passenger; each 288 items adds a crate. Counts individual items, excluding nested contents. Doors stay open while loading. Close this screen to resume service.',
        'The current condition makes it impossible to load the cargo.',
        'Move passengers or animals away from the rear cargo area before loading more.',
        'Easy Elevator', 'Elevator Rail', 'Landing Door', 'Elevator Cabin', 'Elevator Cabin',
        'High-Speed Elevator Cabin', 'High-Speed Elevator Cabin',
        'Observation Elevator Cabin', 'Observation Elevator Cabin',
        'Powerful Elevator Cabin', 'Powerful Elevator Cabin',
        'No cabin on this line. Place a cabin on the rail first.', 'Multiple cabins on this line. Remove the extra cabin.',
        'Call queued.', 'Station/line changed or queue full. Check the rails and landing doors.',
        'This line already has a cabin.', 'The cabin requires a clear, loaded 3 x 3 x 3 space.',
        'Enter and right-click to select a station. Sneak + empty-hand right-click recovers an empty cabin.', 'Destination queued.',
        'The open-door button only works while the cabin is parked at a station.',
        'Cannot close now: the doors are already shut or the cabin is moving.',
        'Elevator call', 'Call going up', 'Call going down',
        'Station height Y = %s', 'Close',
        '%s call registered.',
        'This landing is now floor 1; the other floors were renumbered around it.',
        'Base floor cleared; numbering went back to "lowest landing is floor 1".',
        'Select a station', 'Station %s  /  Y = %s', 'Height %s  |  %s', '%s stations  /  Page %s of %s',
        'No complete landing doors on this line', 'Open', 'Close', 'Previous page', 'Next page',
        'Doors open', 'Closing doors', 'Moving', 'Opening doors', 'Paused: check rails or obstacles',
        # overload (powerful cabin only) + the rated-load plate inside it
        'Overload',
        'Max %s people',
        'Going up', 'Going down', 'Parked',
        'Elevator running', 'Elevator arriving',
        'Elevator arriving (custom)',
        # the per-door arrival-sound settings panel (sneak + right-click a landing door)
        'Landing door settings', 'Station height Y = %s', 'Station height Y = %s  /  Floor %s',
        'Arrival sound',
        'Drag an .ogg into the window, or paste its path below (max 512 KiB)',
        'Full path to the audio file',
        'Upload',
        '%s on', '%s off', '<', '>', 'Choose file...', 'Preview',
        'Default sound', '%s (off)', '> %s',
        'Set as base floor', 'Base floor (floor 1)',
        'Sound id: %s', 'Audio file: %s',
        'Uploaded the %s; syncing to the whole server.',
        'Upload failed: choose an .ogg file of 512 KiB or less.',
        'This game process cannot show a file dialog; check your launcher arguments (do not disable system windows) and try again.',
    ], strict=True))
    zh['message.easyelevator.door_placement'] = '门底部中心必须在轨道朝向前方3格处，并留出3格宽、3格高空间。'
    en['message.easyelevator.door_placement'] = 'Place the bottom centre 3 blocks in front of a rail, with a clear 3 by 3 doorway.'
    write(ASSETS / 'lang/zh_cn.json', zh)
    write(ASSETS / 'lang/en_us.json', en)
    print('Generated blockstates, translations, recipes, loot tables and silent sound hooks.')


if __name__ == '__main__':
    main()
