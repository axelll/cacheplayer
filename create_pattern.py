from PIL import Image, ImageDraw
import os

# Создаем директорию, если она не существует
os.makedirs("app/src/main/res/drawable-nodpi", exist_ok=True)

# Создаем изображение с узором
def create_pattern(size, color1, color2, filename):
    img = Image.new('RGBA', size, color1)
    draw = ImageDraw.Draw(img)
    
    # Рисуем точки
    for x in range(0, size[0], 10):
        for y in range(0, size[1], 10):
            draw.point((x, y), fill=color2)
    
    img.save(filename)

# Создаем узор из точек
create_pattern((20, 20), (10, 21, 37, 255), (15, 31, 48, 255), "app/src/main/res/drawable-nodpi/pattern_dots.png")

# Создаем узор из линий
img = Image.new('RGBA', (20, 20), (10, 21, 37, 255))
draw = ImageDraw.Draw(img)
for i in range(0, 20, 5):
    draw.line([(0, i), (20, i)], fill=(15, 31, 48, 255), width=1)
    draw.line([(i, 0), (i, 20)], fill=(15, 31, 48, 255), width=1)
img.save("app/src/main/res/drawable-nodpi/pattern_grid.png")

print("Patterns created successfully!")
