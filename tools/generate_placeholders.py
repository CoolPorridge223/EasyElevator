"""Generate intentionally plain replacement assets; run with Python 3, no dependencies."""
from pathlib import Path
import json, struct, zlib

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
ASSETS = ROOT / 'assets/easyelevator'

def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def png(path, size, color):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    raw = b''.join(b'\0' + bytes(color) * size for _ in range(size))
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))

def element(a, b):
    return {'from': a, 'to': b, 'faces': {d: {'texture': '#all'} for d in ['north', 'south', 'east', 'west', 'up', 'down']}}

for name, parts in {
    'elevator_rail': [element([5,0,5],[11,16,11])],
    'call_button': [element([5,5,13],[11,11,16])],
}.items():
    write(ASSETS / f'models/block/{name}.json', {'textures': {'all': 'easyelevator:block/blank', 'particle': 'easyelevator:block/blank'}, 'elements': parts})
    write(ASSETS / f'blockstates/{name}.json', {'variants': {f'facing={d}': {'model': f'easyelevator:block/{name}', 'y': r} for d, r in [('north',0),('east',90),('south',180),('west',270)]}})
    write(ASSETS / f'models/item/{name}.json', {'parent': f'easyelevator:block/{name}'})
    write(ROOT / f'data/easyelevator/loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'easyelevator:{name}'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})

write(ASSETS / 'models/item/cabin.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'easyelevator:block/blank'}})
png(ASSETS / 'textures/block/blank.png', 16, (242,242,242,255))
png(ASSETS / 'textures/entity/cabin.png', 16, (255,255,255,255))
png(ASSETS / 'icon.png', 32, (240,240,240,255))
write(ASSETS / 'sounds.json', {key: {'subtitle': f'subtitles.easyelevator.{key}', 'sounds': []} for key in ['elevator_running','elevator_arrival','door_open','door_close']})

zh = {
    'itemGroup.easyelevator': '简易电梯', 'block.easyelevator.elevator_rail': '电梯轨道',
    'block.easyelevator.call_button': '电梯呼叫按钮', 'item.easyelevator.cabin': '电梯轿厢', 'entity.easyelevator.cabin': '电梯轿厢',
    'message.easyelevator.no_cabin': '这条线路没有轿厢，请先在轨道上放置轿厢。',
    'message.easyelevator.multiple_cabins': '这条线路存在多个轿厢，请移除多余轿厢。',
    'message.easyelevator.called': '呼叫已加入队列。', 'message.easyelevator.invalid_stop': '站点或线路已变化，或请求队列已满，请检查轨道和按钮。',
    'message.easyelevator.existing_cabin': '这条线路已有轿厢。', 'message.easyelevator.obstructed': '轿厢需要 3×3×3 的空位，且所在区块必须已加载。',
    'message.easyelevator.enter': '请进入轿厢后右键选站；空手潜行右键可回收空轿厢。', 'message.easyelevator.selected': '目的站已加入队列。',
    'screen.easyelevator.title': '电梯选站', 'screen.easyelevator.station': '站点 %s  ·  Y = %s',
    'screen.easyelevator.status': '高度 %s  |  %s', 'screen.easyelevator.count': '%s 个站点  ·  第 %s / %s 页',
    'screen.easyelevator.empty': '同一线路尚未安装呼叫按钮',
    'phase.easyelevator.open': '开门停靠', 'phase.easyelevator.closing': '正在关门', 'phase.easyelevator.moving': '运行中',
    'phase.easyelevator.opening': '正在开门', 'phase.easyelevator.blocked': '暂停：请检查轨道或障碍',
    'subtitles.easyelevator.elevator_running': '电梯运行', 'subtitles.easyelevator.elevator_arrival': '电梯到站',
    'subtitles.easyelevator.door_open': '电梯开门', 'subtitles.easyelevator.door_close': '电梯关门',
}
en = dict(zip(zh, [
    'Easy Elevator','Elevator Rail','Call Button','Elevator Cabin','Elevator Cabin',
    'No cabin on this line. Place a cabin on the rail first.', 'Multiple cabins on this line. Remove the extra cabin.',
    'Call queued.', 'Station/line changed or queue full. Check the rails and buttons.',
    'This line already has a cabin.', 'The cabin requires a clear, loaded 3 x 3 x 3 space.',
    'Enter and right-click to select a station. Sneak + empty-hand right-click recovers an empty cabin.', 'Destination queued.',
    'Select a station','Station %s  /  Y = %s','Height %s  |  %s','%s stations  /  Page %s of %s',
    'No call buttons on this line','Doors open','Closing doors','Moving','Opening doors','Paused: check rails or obstacles',
    'Elevator running','Elevator arriving','Elevator door opening','Elevator door closing'
], strict=True))
write(ASSETS / 'lang/zh_cn.json', zh)
write(ASSETS / 'lang/en_us.json', en)

recipes = {
    'elevator_rail': (['I I','IRI','I I'], {'I':'iron_ingot','R':'redstone'}, 8),
    'call_button': ([' B ','IRI','   '], {'B':'stone_button','I':'iron_ingot','R':'redstone'}, 2),
    'cabin': (['III','IRI','IPI'], {'I':'iron_ingot','R':'redstone','P':'piston'}, 1),
}
for name, (pattern, keys, count) in recipes.items():
    write(ROOT / f'data/easyelevator/recipe/{name}.json', {'type':'minecraft:crafting_shaped','category':'redstone', 'pattern':pattern,'key':{k:{'item':f'minecraft:{v}'} for k,v in keys.items()},'result':{'id':f'easyelevator:{name}','count':count}})
write(ROOT / 'data/minecraft/tags/block/mineable/pickaxe.json', {'replace':False,'values':['easyelevator:elevator_rail','easyelevator:call_button']})
print('Generated blank models, textures, translations, recipes, loot and silent sound hooks.')
