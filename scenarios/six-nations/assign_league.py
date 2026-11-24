"""Assign Liga Austral to a Minecraft account before starting the scenario server."""
import argparse,json,os,tempfile,uuid
from pathlib import Path

parser=argparse.ArgumentParser(description="Assign Liga Austral while the server is offline.")
parser.add_argument("world",type=Path)
parser.add_argument("uuid",type=uuid.UUID)
args=parser.parse_args()
path=args.world/'modserver/nations.json'
data=json.loads(path.read_text(encoding='utf-8'));nations=data['nations']
league=next(n for n in nations if n['id']=='70b8a5df-3611-54e3-9acb-bc1942e9fb12')
owner=str(args.uuid)
if any(n['id']!=league['id'] and (owner==n['owner'] or owner in n.get('members',{})) for n in nations):
    parser.error("This account already belongs to another nation.")
if league['owner']!=owner:
    league.setdefault('members',{}).pop(league['owner'],None)
    league['members'][owner]='OWNER';league['owner']=owner
    with tempfile.NamedTemporaryFile('w',encoding='utf-8',dir=path.parent,delete=False) as f:
        json.dump(data,f,ensure_ascii=False,indent=2)
    os.replace(f.name,path)
print('Liga Austral owner:',owner)
