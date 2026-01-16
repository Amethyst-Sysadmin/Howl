import struct
from dataclasses import dataclass
from typing import List

# ============================================================
#  HWL format constants
# ============================================================

HWL_HEADER = b"YEAHBOI!"
HWL_HEADER_SIZE = len(HWL_HEADER)

# Each pulse = 4 floats = 16 bytes
HWL_PULSE_SIZE = 16
HWL_PULSES_PER_SECOND = 40
HWL_PULSE_TIME = 1.0/HWL_PULSES_PER_SECOND


# ============================================================
#  Pulse definition
# ============================================================

@dataclass
class Pulse:
    """
    Represents an individual pulse within an HWL file.
    All fields have a valid range of 0.0 to 1.0.
    """
    left_freq: float
    right_freq: float
    left_amp: float
    right_amp: float


# ============================================================
#  HWL read/write functions
# ============================================================

def read_hwl_file(filename: str) -> List[Pulse]:
    """
    Read an HWL file and return a list of Pulses.
    
    An HWL file simply consists of an 8 byte header that always says "YEAHBOI!"
    followed by any number of pulse objects (one for every 1/40th second the file lasts)

    Each pulse object consists of 4x IEEE 754 single precision floating point values in little endian format, as follows: -
    left_channel_amplitude: 0.0 to 1.0
    right_channel_amplitude: 0.0 to 1.0
    left_channel_frequency: 0.0 to 1.0
    right_channel_frequency: 0.0 to 1.0
    """
    pulses = []

    with open(filename, 'rb') as f:
        header = f.read(HWL_HEADER_SIZE)
        if header != HWL_HEADER:
            raise ValueError(f"{filename} is not a valid HWL file (bad header)")

        while True:
            chunk = f.read(HWL_PULSE_SIZE)
            if len(chunk) == 0:
                break
            if len(chunk) != HWL_PULSE_SIZE:
                raise ValueError(f"Corrupted HWL file: truncated pulse data in {filename}")

            left_amp, right_amp, left_freq, right_freq = struct.unpack('<ffff', chunk)
            pulses.append(Pulse(left_freq, right_freq, left_amp, right_amp))

    return pulses


def write_hwl_file(destination_filename: str, pulses: List[Pulse]):
    """
    Write a list of pulses to an HWL file
    """
    if not pulses:
        raise ValueError("No pulses to write")

    with open(destination_filename, 'wb') as file:
        file.write(HWL_HEADER)

        for p in pulses:
            file.write(struct.pack(
                '<ffff',
                p.left_amp,
                p.right_amp,
                p.left_freq,
                p.right_freq
            ))