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
}.items():
    write(ASSETS / f'models/block/{name}.json', {'textures': {'all': 'easyelevator:block/blank', 'particle': 'easyelevator:block/blank'}, 'elements': parts})
    write(ASSETS / f'blockstates/{name}.json', {'variants': {f'facing={d}': {'model': f'easyelevator:block/{name}', 'y': r} for d, r in [('north',0),('east',90),('south',180),('west',270)]}})
    write(ASSETS / f'models/item/{name}.json', {'parent': f'easyelevator:block/{name}'})
    write(ROOT / f'data/easyelevator/loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'easyelevator:{name}'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})

# 楼层电梯门：一件物品生成 3x3 的整扇门。旧的 call_button 注册 ID 刻意保留，兼容旧存档与旧物品。
# 门面拆成两部分资源：
#   门框：landing_door_frame_*.json（常驻，亮白 blank 贴图），开与关都用同一套模型，
#         因此 blockstate 里 open=true/false 指向同一个模型——外观变化全部交给门扇渲染器；
#         顶行的左右两列必须同时带上门楣与立柱（top_left / top_right），否则立柱会在离门楣
#         13/16 格处断开，看起来像三段分离的框；
#   门扇：由 LandingDoorRenderer 按连续进度绘制（暗白 blank_dark 贴图），几何与
#         LandingDoorGeometry.leafBox 同源，所以这里不需要任何门扇模型；
#   call_button.json：关门状态的整扇门（门楣 + 两扇带中缝的门扇），只给物品栏图标使用，
#         方块本身永远不会再显示它。
FRAME = 3
SEAM = 1                     # 与 LandingDoorGeometry.SEAM 一致，单位 1/16 格
DOOR_WIDTH = 48              # 3 格宽，单位 1/16 格
LEAF_TRAVEL = (DOOR_WIDTH - 2 * FRAME - SEAM) / 2
LEAF_INNER_LEFT = FRAME + LEAF_TRAVEL            # 左扇右缘（整扇门坐标）
LEAF_INNER_RIGHT = DOOR_WIDTH - FRAME - LEAF_TRAVEL  # 右扇左缘（整扇门坐标）
def door_element(a, b, texture):
    e = element(a, b)
    for face in e['faces']:
        e['faces'][face]['texture'] = texture
    return e

write(ASSETS / 'models/block/call_button.json', {
    'textures': {'all': 'easyelevator:block/blank', 'dark': 'easyelevator:block/blank_dark', 'particle': 'easyelevator:block/blank'},
    'elements': [
        door_element([0,13,0],[16,16,3],'#all'),   # 门楣（门框）
        # 两扇门扇落在根方块（中列）内的部分：整扇门坐标 u 减去 16 即本格坐标
        door_element([0,0,0],[LEAF_INNER_LEFT-16,16,3],'#dark'),
        door_element([LEAF_INNER_RIGHT-16,0,0],[16,16,3],'#dark'),
    ],
})
write(ASSETS / 'models/item/call_button.json', {'parent': 'easyelevator:block/call_button'})
for name, parts in {
    'landing_door_frame_left': [element([0,0,0],[3,16,3])],
    'landing_door_frame_right': [element([13,0,0],[16,16,3])],
    'landing_door_frame_top': [element([0,13,0],[16,16,3])],
    # 顶行左右两列：立柱一直顶到门楣，门框才是连着的一整圈
    'landing_door_frame_top_left': [element([0,0,0],[3,16,3]), element([0,13,0],[16,16,3])],
    'landing_door_frame_top_right': [element([13,0,0],[16,16,3]), element([0,13,0],[16,16,3])],
    'landing_door_frame_middle': [],
}.items():
    write(ASSETS / f'models/block/{name}.json', {'textures': {'all':'easyelevator:block/blank','particle':'easyelevator:block/blank'}, 'elements':parts})
variants={}
for facing, rotation in [('north',0),('east',90),('south',180),('west',270)]:
    for col in range(3):
        for row in range(3):
            # 门框模型只由列/层决定：顶行是门楣（左右列还要带上立柱），左右列是立柱，中列（门洞）为空
            if row == 2:
                model = 'landing_door_frame_top' if col == 1 else 'landing_door_frame_top_' + ('left' if col == 0 else 'right')
            else:
                model = 'landing_door_frame_' + ['left', 'middle', 'right'][col]
            # 保留 open 属性（联锁状态仍要同步给客户端），但两个取值指向同一个模型
            for opened in [False,True]:
                variants[f'facing={facing},column={col},level={row},open={str(opened).lower()}']={'model':f'easyelevator:block/{model}','y':rotation}
write(ASSETS / 'blockstates/call_button.json',{'variants':variants})
# 自检：门框模型必须与 LandingDoorGeometry 的约定一致——门框厚 3/16 格、门楣在本格 13..16、
# 顶行左右两列同时带立柱（0..3 / 13..16，通到本格底部）与门楣。少了立柱，门框在画面上会在
# 离门楣 13/16 格处断开，看起来像三段分离的框；这里让不一致直接报错而不是默默生成坏资源。
expected_frame_models = {
    'landing_door_frame_left': [[[0,0,0],[3,16,3]]],
    'landing_door_frame_right': [[[13,0,0],[16,16,3]]],
    'landing_door_frame_top': [[[0,13,0],[16,16,3]]],
    'landing_door_frame_top_left': [[[0,0,0],[3,16,3]], [[0,13,0],[16,16,3]]],
    'landing_door_frame_top_right': [[[13,0,0],[16,16,3]], [[0,13,0],[16,16,3]]],
    'landing_door_frame_middle': [],
}
for name, want in expected_frame_models.items():
    model = json.loads((ASSETS / f'models/block/{name}.json').read_text(encoding='utf-8'))
    got = [[e['from'], e['to']] for e in model['elements']]
    assert got == want, f'{name}: elements {got} != expected {want}'
write(ROOT / 'data/easyelevator/loot_table/blocks/call_button.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'easyelevator:call_button'}],'conditions':[{'condition':'minecraft:survives_explosion'},{'condition':'minecraft:block_state_property','block':'easyelevator:call_button','properties':{'column':'1','level':'0'}}]}]})

write(ASSETS / 'models/item/cabin.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'easyelevator:block/blank'}})
png(ASSETS / 'textures/block/blank.png', 16, (242,242,242,255))
# 门扇占位贴图：比门框略暗，让"门框"与"门"在纯白占位阶段也能分辨
png(ASSETS / 'textures/block/blank_dark.png', 16, (200,200,200,255))
png(ASSETS / 'textures/entity/cabin.png', 16, (255,255,255,255))
png(ASSETS / 'icon.png', 32, (240,240,240,255))
write(ASSETS / 'sounds.json', {key: {'subtitle': f'subtitles.easyelevator.{key}', 'sounds': []} for key in ['elevator_running','elevator_arrival','door_open','door_close']})

zh = {
    'itemGroup.easyelevator': '简易电梯', 'block.easyelevator.elevator_rail': '电梯轨道',
    'block.easyelevator.call_button': '电梯门', 'item.easyelevator.cabin': '电梯轿厢', 'entity.easyelevator.cabin': '电梯轿厢',
    'message.easyelevator.no_cabin': '这条线路没有轿厢，请先在轨道上放置轿厢。',
    'message.easyelevator.multiple_cabins': '这条线路存在多个轿厢，请移除多余轿厢。',
    'message.easyelevator.called': '呼叫已加入队列。', 'message.easyelevator.invalid_stop': '站点或线路已变化，或请求队列已满，请检查轨道和电梯门。',
    'message.easyelevator.existing_cabin': '这条线路已有轿厢。', 'message.easyelevator.obstructed': '轿厢需要 3×3×3 的空位，且所在区块必须已加载。',
    'message.easyelevator.enter': '请进入轿厢后右键选站；空手潜行右键可回收空轿厢。', 'message.easyelevator.selected': '目的站已加入队列。',
    'message.easyelevator.door_no_station': '开门键只在轿厢停在某一层时有效。',
    'message.easyelevator.door_close_locked': '当前不能关门：门已经关好，或轿厢正在运行。',
    'screen.easyelevator.title': '电梯选站', 'screen.easyelevator.station': '站点 %s  ·  Y = %s',
    'screen.easyelevator.status': '高度 %s  |  %s', 'screen.easyelevator.count': '%s 个站点  ·  第 %s / %s 页',
    'screen.easyelevator.empty': '同一线路尚未安装完整电梯门',
    'screen.easyelevator.floor': '第 %s 层',
    'screen.easyelevator.open_door': '开门', 'screen.easyelevator.close_door': '关门',
    'phase.easyelevator.open': '开门停靠', 'phase.easyelevator.closing': '正在关门', 'phase.easyelevator.moving': '运行中',
    'phase.easyelevator.opening': '正在开门', 'phase.easyelevator.blocked': '暂停：请检查轨道或障碍',
    'status.easyelevator.up': '电梯上行', 'status.easyelevator.down': '电梯下行', 'status.easyelevator.idle': '停靠',
    'subtitles.easyelevator.elevator_running': '电梯运行', 'subtitles.easyelevator.elevator_arrival': '电梯到站',
    'subtitles.easyelevator.door_open': '电梯开门', 'subtitles.easyelevator.door_close': '电梯关门',
}
en = dict(zip(zh, [
    'Easy Elevator','Elevator Rail','Landing Door','Elevator Cabin','Elevator Cabin',
    'No cabin on this line. Place a cabin on the rail first.', 'Multiple cabins on this line. Remove the extra cabin.',
    'Call queued.', 'Station/line changed or queue full. Check the rails and landing doors.',
    'This line already has a cabin.', 'The cabin requires a clear, loaded 3 x 3 x 3 space.',
    'Enter and right-click to select a station. Sneak + empty-hand right-click recovers an empty cabin.', 'Destination queued.',
    'The open-door button only works while the cabin is parked at a station.',
    'Cannot close now: the doors are already shut or the cabin is moving.',
    'Select a station','Station %s  /  Y = %s','Height %s  |  %s','%s stations  /  Page %s of %s',
    'No complete landing doors on this line','Floor %s','Open','Close','Doors open','Closing doors','Moving','Opening doors','Paused: check rails or obstacles',
    'Going up','Going down','Parked',
    'Elevator running','Elevator arriving','Elevator door opening','Elevator door closing'
], strict=True))
zh['message.easyelevator.door_placement']='门底部中心必须在轨道朝向前方3格处，并留出3格宽、3格高空间。'
en['message.easyelevator.door_placement']='Place the bottom centre 3 blocks in front of a rail, with a clear 3 by 3 doorway.'
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
