#!/usr/bin/env python3
import argparse
from libhwl import read_hwl_file, write_hwl_file, HWL_PULSES_PER_SECOND, Pulse

def parse_time(value: str) -> float:
    """
    Parse time in any of these formats:
      - SS
      - SS.sss
      - MM:SS
      - MM:SS.sss
      - HH:MM:SS
      - HH:MM:SS.sss

    Returns time in seconds (float).
    """
    parts = value.split(":")
    if len(parts) == 1:
        # Seconds only
        return float(parts[0])

    elif len(parts) == 2:
        # MM:SS
        minutes = int(parts[0])
        seconds = float(parts[1])
        return minutes * 60 + seconds

    elif len(parts) == 3:
        # HH:MM:SS
        hours = int(parts[0])
        minutes = int(parts[1])
        seconds = float(parts[2])
        return hours * 3600 + minutes * 60 + seconds

    else:
        raise ValueError(f"Invalid time format: {value}")
        
        
def format_duration(seconds: float) -> str:
    """
    Format duration in seconds to human-readable format HH:MM:SS or MM:SS,
    including fractional seconds up to 2 decimals if non-zero.
    Uses integer arithmetic to avoid floating-point issues.
    """
    # Convert total seconds to centiseconds (hundredths of a second)
    total_cs = int(round(seconds * 100))

    # Compute hours, minutes, seconds, centiseconds
    hours, remainder_cs = divmod(total_cs, 3600 * 100)
    minutes, remainder_cs = divmod(remainder_cs, 60 * 100)
    secs, cs = divmod(remainder_cs, 100)

    # Build the seconds string with optional fractional part
    if cs > 0:
        sec_str = f"{secs:02d}.{cs:02d}"
    else:
        sec_str = f"{secs:02d}"

    if hours > 0:
        return f"{hours:02d}:{minutes:02d}:{sec_str}"
    else:
        return f"{minutes:02d}:{sec_str}"

def cmd_silence(args):
    """Silence command"""
    pulses = read_hwl_file(args.infile)

    duration_sec = parse_time(args.duration)
    silence_pulses = int(round(duration_sec * HWL_PULSES_PER_SECOND))
    if silence_pulses <= 0:
        raise ValueError("Silence duration is too short to produce any pulses")
    
    # Create silence pulses (all fields zero)
    silence = [
        Pulse(0.0, 0.0, 0.0, 0.0)
        for _ in range(silence_pulses)
    ]

    output = pulses + silence
    write_hwl_file(args.out, output)

def cmd_append(args):
    """Append command"""
    base_pulses = read_hwl_file(args.infile)
    add_pulses = read_hwl_file(args.add)

    output = base_pulses[:]

    for _ in range(args.repeats):
        output.extend(add_pulses)

    write_hwl_file(args.out, output)
    
def cmd_extract(args):
    """Extract command"""
    pulses = read_hwl_file(args.infile)

    start_sec = parse_time(args.start)
    end_sec = parse_time(args.end) if args.end is not None else None

    # Convert to pulse indices (pulses are 1/40th of a second)
    # Start time is inclusive and end time is exclusive if specified
    start_index = int(start_sec * HWL_PULSES_PER_SECOND)

    if end_sec is not None:
        end_index = int(end_sec * HWL_PULSES_PER_SECOND) - 1
    else:
        end_index = len(pulses) - 1

    if start_index < 0 or start_index >= len(pulses):
        raise ValueError("Start time is outside the source file")
        
    if end_index < 0 or end_index >= len(pulses):
        raise ValueError("End time is outside the source file")

    if end_index < start_index:
        raise ValueError("End time is earlier than start time")

    extracted = pulses[start_index : end_index + 1]

    if not extracted:
        raise ValueError("No pulses extracted (empty range)")

    write_hwl_file(args.out, extracted)
    
    
def cmd_info(args):
    """Info command"""
    pulses = read_hwl_file(args.infile)
    
    # Calculate durations
    duration_pulses = len(pulses)
    duration_seconds = duration_pulses / HWL_PULSES_PER_SECOND
    human_duration = format_duration(duration_seconds)
    
    # Print nicely formatted output
    print(f"HWL File Information")
    print(f"====================")
    print(f"File: {args.infile}")
    print(f"Number of pulses: {duration_pulses}")
    print(f"Duration (seconds): {duration_seconds:.2f}")
    print(f"Duration (readable): {human_duration}")


def main():
    parser = argparse.ArgumentParser(
        prog="hwltools",
        description="Tools for working with HWL pulse files"
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    # silence command
    silence_parser = subparsers.add_parser("silence", help="Append silence to an HWL file")
    silence_parser.add_argument("--in", dest="infile", required=True, help="Base HWL file")
    silence_parser.add_argument("--duration", required=True, help="Duration of silence to add (e.g. 1.5, 2, 1:00)")
    silence_parser.add_argument("--out", required=True, help="Output HWL file")
    silence_parser.set_defaults(func=cmd_silence)
    
    # append command
    append_parser = subparsers.add_parser("append", help="Append one HWL file to another")
    append_parser.add_argument("--in", dest="infile", required=True, help="Base HWL file")
    append_parser.add_argument("--add", required=True, help="HWL file to append")
    append_parser.add_argument("--out", required=True, help="Output HWL file")
    append_parser.add_argument(
        "--repeats", "-r", type=int, default=1,
        help="Number of times to append the add file (default: 1)"
    )
    append_parser.set_defaults(func=cmd_append)
    
    # extract command
    extract_parser = subparsers.add_parser("extract", help="Extract a section of an HWL file")
    extract_parser.add_argument("--in", dest="infile", required=True, help="Source HWL file")
    extract_parser.add_argument("--start", required=True, help="Start time (e.g. 20, 32.25, 5:25)")
    extract_parser.add_argument("--end", help="End time (uses end of file if not supplied)")
    extract_parser.add_argument("--out", required=True, help="Output HWL file")
    extract_parser.set_defaults(func=cmd_extract)
    
    # info command
    info_parser = subparsers.add_parser("info", help="Get information about an HWL file")
    info_parser.add_argument("--in", dest="infile", required=True, help="HWL file to get info on")
    info_parser.set_defaults(func=cmd_info)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()