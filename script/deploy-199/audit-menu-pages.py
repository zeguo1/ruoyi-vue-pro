#!/usr/bin/env python3
"""Audit a menu snapshot against reachable deployed Vite assets. Never writes to a DB."""
import argparse
import json
from pathlib import Path
import re


def deployed_views(root):
    queue = [x.lstrip('/') for x in re.findall(
        r'(?:src|href)="([^"]+\.js)"', (root / 'index.html').read_text())]
    seen, views = set(), set()
    while queue:
        relative = queue.pop()
        if relative in seen:
            continue
        asset = root / relative
        if not asset.is_file():
            continue
        seen.add(relative)
        content = asset.read_text()
        views.update(re.findall(r'\.\./views/([^"\s]+\.vue)', content))
        for ref in re.findall(r'["\']([^"\']+\.js)["\']', content):
            if ref.startswith('./'):
                queue.append(str(Path(relative).parent / ref[2:]))
            elif ref.startswith(('js/', '/js/')):
                queue.append(ref.lstrip('/'))
    if not views:
        raise ValueError('No Vite view map found; refusing to infer missing pages')
    return views


def audit(rows, views):
    missing = []
    for row in rows:
        component = row.get('component') or ''
        if row['type'] != 2 or row['status'] != 0 or not component or component.startswith(('http:', 'https:')):
            continue
        component = component.lstrip('/')
        if not component.endswith('.vue'):
            component += '.vue'
        if component not in views:
            missing.append(row)
    disabled = {row['id'] for row in missing}
    parents = {row['parentId'] for row in missing}
    folders = []
    while True:
        added = []
        for row in rows:
            if row['type'] != 1 or row['status'] != 0 or row['id'] not in parents or row['id'] in disabled:
                continue
            children = [r for r in rows if r['parentId'] == row['id'] and r['type'] in (1, 2)
                        and r['status'] == 0 and r['id'] not in disabled]
            if not children:
                added.append(row)
        if not added:
            break
        folders.extend(added)
        disabled.update(row['id'] for row in added)
        parents.update(row['parentId'] for row in added)
    return missing, folders


def sql(rows, restore=False):
    lines = ['-- Reviewed deployment availability changes only; role assignments and business data are preserved.',
             'SET NAMES utf8mb4;', 'START TRANSACTION;']
    for row in rows:
        # Hex literals avoid quoting and connection-encoding ambiguity in guards.
        component = row.get('component')
        guard = 'component IS NULL' if component is None else f"HEX(component)='{component.encode().hex().upper()}'"
        old_status, old_visible = (1, 0) if restore else (row['status'], row['visible'])
        new_status, new_visible = (row['status'], row['visible']) if restore else (1, 0)
        lines.append(f"UPDATE system_menu SET status={new_status},visible={new_visible} WHERE id={row['id']} "
                     f"AND deleted=0 AND type={row['type']} AND {guard} AND status={old_status} AND visible={old_visible};")
    lines.append('COMMIT;')
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('menu_snapshot', type=Path, help='JSON array with camelCase menu properties')
    parser.add_argument('frontend_dist', type=Path)
    parser.add_argument('output_directory', type=Path)
    args = parser.parse_args()
    rows = json.loads(args.menu_snapshot.read_text())
    views = deployed_views(args.frontend_dist)
    missing, folders = audit(rows, views)
    args.output_directory.mkdir(parents=True, exist_ok=True)
    (args.output_directory / 'audit.json').write_text(json.dumps(
        {'deployedViewCount': len(views), 'missingPages': missing, 'emptyParentFolders': folders},
        ensure_ascii=False, indent=2) + '\n')
    for filename, restore in [('downlist.sql', False), ('restore.sql', True)]:
        (args.output_directory / filename).write_text(sql(missing + folders, restore))
    print(f'{len(missing)} missing pages; {len(folders)} resulting empty folders. SQL generated only, not applied.')


if __name__ == '__main__':
    main()
