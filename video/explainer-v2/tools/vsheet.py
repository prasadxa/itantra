import sys
from PIL import Image, ImageDraw
out=sys.argv[1]; files=sys.argv[2:]
W,H=330,586
sheet=Image.new("RGB",(W*len(files),H+30),(40,40,40)); d=ImageDraw.Draw(sheet)
for i,f in enumerate(files):
    im=Image.open(f).convert("RGB").resize((W-6,H-6)); sheet.paste(im,(i*W+3,33)); d.text((i*W+6,8),f.split("/")[-1],fill=(255,255,255))
sheet.save(out, quality=88)
