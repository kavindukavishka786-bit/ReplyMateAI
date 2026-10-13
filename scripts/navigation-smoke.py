"""Offline CI navigation smoke test; no API key or live WhatsApp messages."""
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree as ET

for label in ['Rules', 'Reviews', 'Settings', 'Home']:
    subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/window.xml'], check=True)
    xml = subprocess.check_output(['adb', 'shell', 'cat', '/sdcard/window.xml'], text=True)
    nodes = [n for n in ET.fromstring(xml).iter('node')
             if n.get('content-desc') == label or n.get('text') == label]
    assert nodes, f'Missing navigation tab: {label}'
    bounds = [tuple(map(int, re.findall(r'\d+', n.attrib['bounds']))) for n in nodes]
    left, top, right, bottom = max(bounds, key=lambda b: b[1])
    subprocess.run(['adb', 'shell', 'input', 'tap', str((left + right) // 2),
                    str((top + bottom) // 2)], check=True)
    time.sleep(1)
    subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/window.xml'], check=True)
    displayed = subprocess.check_output(['adb', 'shell', 'cat', '/sdcard/window.xml'], text=True)
    Path(f'smoke/{label.lower()}.xml').write_text(displayed)
    # Validate destination content, not just a successful tap.
    expected = {'Rules': 'Your rules', 'Reviews': 'Review inbox',
                'Settings': 'AI settings', 'Home': 'Overview'}[label]
    assert expected in displayed, f'Tab did not open: {label}'
