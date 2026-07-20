#!/usr/bin/env python3
"""
Batch combine legacy funscripts with multiple files into single file multi-axis funscripts.

These multi-axis scripts are generally used with advanced stroker robots such as the OSR2 and
SR6, and can also be used with particularly cool estim software like Howl ;)

Place all the funscripts you want combined in a "funscripts" directory under the script path.

Your additional axis files must be named correctly, sharing the name of the main script
and a correct extension. A full set of files with every supported axis would look like:
example.funscript (L0 axis - main script with our up/down strokes)
example.surge.funscript (L1 axis - forward/back)
example.sway.funscript (L2 axis - left/right)
example.twist.funscript (R0 axis - twist rotation)
example.roll.funscript (R1 axis - roll rotation)
example.pitch.funscript (R2 axis - pitch rotation)
example.vib.funscript (V0 axis - vibration)
example.pump.funscript (V1 axis - pump [use not recommended as superseded by A2])
example.valve.funscript (A0 axis - valve)
example.suck.funscript (A1 axis - suction)
example.lube.funscript (A2 axis - lube)

The resulting merged file will be written as example.combined.funscript.

Grouping is fairly smart, if any files are incorrectly named the script will typically
spot that and complain.

You can specify the "--delete" command line flag to nuke the original funscripts after combining,
if you like to live dangerously.

The script can also be used to add additional axes to an existing multi-axis main funscript.
"""

import argparse
import sys
import json
import uuid
from pathlib import Path
from collections import defaultdict

# Standard axis mapping and ordered output list
AXIS_ORDER = ['L1', 'L2', 'R0', 'R1', 'R2', 'V0', 'V1', 'A0', 'A1', 'A2']
AXIS_MAP = {
    'surge': 'L1',
    'sway': 'L2',
    'twist': 'R0',
    'roll': 'R1',
    'pitch': 'R2',
    'vib': 'V0',
    'pump': 'V1',
    'valve': 'A0',
    'suck': 'A1',
    'lube': 'A2'
}

def scan_and_group_files(scripts_dir: Path):
    """Scans the directory for funscripts and groups them logically by their base filename."""
    all_files = [f for f in scripts_dir.iterdir() if f.is_file()]
    groups = defaultdict(dict)

    for f in all_files:
        name_lower = f.name.lower()

        # Ignore combined scripts from previous runs so they can be cleanly overwritten later
        if name_lower.endswith('.combined.funscript'):
            continue

        # Only process recognized funscript files
        if not name_lower.endswith('.funscript'):
            continue

        stem = f.stem
        p = Path(stem)
        suffix = p.suffix

        # Check if the file represents a supplementary axis
        if suffix.startswith('.'):
            axis_name = suffix[1:].lower()
            if axis_name in AXIS_MAP:
                axis_id = AXIS_MAP[axis_name]
                base_name = p.stem
                groups[base_name][axis_id] = f
                continue

        # If not a recognized axis suffix, it must be a main script
        base_name = stem
        groups[base_name]['main'] = f

    return all_files, groups

def check_for_orphans(all_files, groups):
    """
    Identifies 'orphan' files: funscript files that don't belong to a valid,
    complete set (i.e., a set that has a main script AND at least one axis).
    """
    valid_group_files = set()

    for base_name, group in groups.items():
        # A valid group must contain a main script and at least one supplementary axis
        if 'main' in group and len(group) > 1:
            for f in group.values():
                valid_group_files.add(f)

    orphans = [
        f for f in all_files
        if f.name.lower().endswith('.funscript')
        and not f.name.lower().endswith('.combined.funscript')
        and f not in valid_group_files
    ]

    return orphans

def prepare_data_for_compact_actions(data):
    """
    Replaces 'actions' lists with unique string placeholders and returns
    a mapping of placeholders to compact JSON strings.
    """
    placeholders = {}

    def walk(obj):
        if isinstance(obj, dict):
            for k, v in obj.items():
                if k == 'actions' and isinstance(v, list):
                    compact = json.dumps(v, separators=(',', ':'))
                    placeholder = f"__ACTIONS_{uuid.uuid4().hex}__"
                    placeholders[placeholder] = compact
                    obj[k] = placeholder
                else:
                    walk(v)
        elif isinstance(obj, list):
            for item in obj:
                walk(item)

    walk(data)
    return placeholders

def combine_scripts(groups, axis_order, delete_originals=False, overwrite_axes=False):
    """Iterates through valid groups, combines the data, and writes the output files."""
    valid_groups_found = False
    axis_order_map = {axis_id: i for i, axis_id in enumerate(axis_order)}

    for base_name, group in groups.items():
        if 'main' not in group or len(group) <= 1:
            continue

        valid_groups_found = True
        main_file = group['main']

        try:
            with open(main_file, 'r', encoding='utf-8') as f:
                main_data = json.load(f)
        except json.JSONDecodeError as e:
            print(f"Error: Failed to parse JSON in main script '{main_file.name}': {e}")
            sys.exit(1)
        except Exception as e:
            print(f"Error: Failed to read main script '{main_file.name}': {e}")
            sys.exit(1)

        axes_data = []
        for axis_id in axis_order:
            if axis_id in group:
                axis_file = group[axis_id]
                try:
                    with open(axis_file, 'r', encoding='utf-8') as f:
                        axis_json = json.load(f)

                    axes_data.append({
                        "id": axis_id,
                        "actions": axis_json.get("actions", [])
                    })
                except json.JSONDecodeError as e:
                    print(f"Error: Failed to parse JSON in axis script '{axis_file.name}': {e}")
                    sys.exit(1)
                except Exception as e:
                    print(f"Error: Failed to read axis script '{axis_file.name}': {e}")
                    sys.exit(1)

        # Handle existing axes in main script
        existing_axes = main_data.get("axes", [])
        if not isinstance(existing_axes, list):
            existing_axes = []

        # Use a dictionary to easily manage overwrites while preserving order of insertion
        merged_axes_map = {}
        for axis in existing_axes:
            if isinstance(axis, dict) and "id" in axis:
                merged_axes_map[axis["id"]] = axis

        skip_group = False
        for new_axis in axes_data:
            if new_axis["id"] in merged_axes_map:
                if not overwrite_axes:
                    print(f"Error: Axis ID '{new_axis['id']}' from '{group[new_axis['id']].name}' clashes with existing axis in main script '{main_file.name}'. Skipping group. Pass --overwrite-axes if you want to destructively merge.")
                    skip_group = True
                    break
                else:
                    print(f"Info: Overwriting existing axis '{new_axis['id']}' in '{main_file.name}' with data from '{group[new_axis['id']].name}'.")
            merged_axes_map[new_axis["id"]] = new_axis

        if skip_group:
            continue

        combined_axes = list(merged_axes_map.values())

        # Sort axes: known axes follow AXIS_ORDER, unknown custom axes are pushed to the end
        combined_axes.sort(key=lambda x: axis_order_map.get(x.get("id"), len(axis_order)))

        main_data["axes"] = combined_axes

        # Prepare compact actions arrays to keep them on a single line
        placeholders = prepare_data_for_compact_actions(main_data)

        # Serialize to indented JSON
        json_str = json.dumps(main_data, indent=2)

        # Inject compact actions arrays
        for p, c in placeholders.items():
            json_str = json_str.replace(f'"{p}"', c)

        combined_filename = main_file.parent / f"{base_name}.combined.funscript"
        try:
            with open(combined_filename, 'w', encoding='utf-8') as f:
                f.write(json_str)
        except Exception as e:
            print(f"Error: Failed to write combined script '{combined_filename.name}': {e}")
            sys.exit(1)

        print(f"Successfully created: {combined_filename.name}")

        if delete_originals:
            for f in group.values():
                try:
                    f.unlink()
                except Exception as e:
                    print(f"Warning: Failed to delete '{f.name}': {e}")
            print(f"Deleted {len(group)} original source files for '{base_name}'.")

    if not valid_groups_found:
        print("No valid funscript sets found to combine.")

def main():
    parser = argparse.ArgumentParser(description="Batch combine single-axis JSON funscripts into multi-axis funscripts.")
    parser.add_argument(
        "directory",
        nargs="?",
        default="funscripts",
        help="The directory containing the funscripts (default: 'scripts')"
    )
    parser.add_argument(
        "-d", "--delete",
        action="store_true",
        help="Delete the original individual funscript files after combining."
    )
    parser.add_argument(
        "-o", "--overwrite-axes",
        action="store_true",
        help="Overwrite existing axes in the main script if an axis ID clashes."
    )
    args = parser.parse_args()

    scripts_dir = Path(args.directory)

    if not scripts_dir.exists() or not scripts_dir.is_dir():
        print(f"Error: Directory '{scripts_dir}' does not exist or is not a valid directory.")
        sys.exit(1)

    # 1. Initial file scan and grouping
    all_files, groups = scan_and_group_files(scripts_dir)

    # 2. Check for orphaned funscripts
    orphans = check_for_orphans(all_files, groups)

    if orphans:
        print("Error: Found orphan funscript files that do not belong to a valid set (missing a main script or axes):")
        for o in orphans:
            print(f"  - {o.name}")
        sys.exit(1)

    # 3. Combine valid groups and write output
    combine_scripts(groups, AXIS_ORDER, args.delete, args.overwrite_axes)

if __name__ == "__main__":
    main()
