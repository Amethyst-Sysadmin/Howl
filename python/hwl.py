import os
import numpy as np
import struct
import itertools
import gc
from pathlib import Path
from aubio import source, pitch
from dataclasses import dataclass

@dataclass
class Sample:
    """
    Represents audio sample data with left and right channel frequencies and amplitudes.
    Used to store our final aggregated data, which we normalise and then write to a file.
    """
    left_freq: float
    right_freq: float
    left_amp: float
    right_amp: float

@dataclass
class HopData:
    """
    Represents audio data for a single processing hop.
    Our TimeBinner will aggregate several of these (8 with the default config) into a Sample.
    """
    left_freq: float
    right_freq: float
    left_sq: float
    right_sq: float
    num_samples: int

class TimeBinner:
    """
    Manages time-based binning (via audio frames) of amplitudes and frequencies.
    Collects HopData into fixed-duration bins and computes aggregate Samples
    when bins are complete.
    """
    def __init__(self, bin_interval, sample_rate):
        self.bin_interval = bin_interval
        self.frames_per_bin = int(bin_interval * sample_rate)
        self.current_bin_end_frame = self.frames_per_bin
        self.hop_data = []  # Stores HopData objects for current bin
        self.binned_data = []  # Stores aggregated Sample objects
    
    def add_hop(self, current_frame, hop_data):
        """
        Add the hop data to the current bin if within time window,
        otherwise finalise current bin and start new one
        """
        if current_frame < self.current_bin_end_frame:
            self.hop_data.append(hop_data)
            return False # Bin not finalised
        
        # Finalise current bin
        self.finalise_bin()
        
        # Start new bin with current sample
        self.hop_data = [hop_data]
        self.current_bin_end_frame += self.frames_per_bin
        return True # Bin was finalised
    
    def finalise_bin(self):
        """Compute aggregate values for current bin and reset collection"""
        if not self.hop_data:
            # Handle empty bin by creating zero-value entry
            binned_sample = Sample(0.0, 0.0, 0.0, 0.0)
        else:
            # print(f"Binning {len(self.hop_data)} samples")
            
            # Extract frequencies for median calculation, ignoring 0.0 values
            # where our pitch detector could not produce an acceptable estimate
            left_freqs = [h.left_freq for h in self.hop_data if h.left_freq != 0.0]
            right_freqs = [h.right_freq for h in self.hop_data if h.right_freq != 0.0]
            
            # Sum squared amplitudes and sample counts
            total_left_sq = sum(h.left_sq for h in self.hop_data)
            total_right_sq = sum(h.right_sq for h in self.hop_data)
            total_samples = sum(h.num_samples for h in self.hop_data)
            
            # Compute median frequencies
            left_freq_med = np.median(left_freqs) if left_freqs else 0.0
            right_freq_med = np.median(right_freqs) if right_freqs else 0.0
            
            # Calculate RMS amplitudes
            left_amp = np.sqrt(total_left_sq / total_samples) if total_samples > 0 else 0.0
            right_amp = np.sqrt(total_right_sq / total_samples) if total_samples > 0 else 0.0
            
            binned_sample = Sample(left_freq_med, right_freq_med, left_amp, right_amp)
        
        self.binned_data.append(binned_sample)
        self.hop_data = []  # Reset current bin
    
    def finalise_all(self):
        """Finalise any remaining samples in current bin and return binned data"""
        if self.hop_data:
            self.finalise_bin()
        self.estimate_missing_frequencies()
        return self.binned_data
        
    def estimate_missing_frequencies(self):
        """
        Sensibly fill in any missing frequency values our pitch detector was unable to estimate (any
        that were set to 0.0).
        Gaps are filled by the last valid frequency we had. For gaps at the beginning of the audio, we
        instead use the first valid frequency.
        """
        if not self.binned_data:
            return
        # Figure out what our initial fallback frequencies will be so that we can also replace leading zeros
        last_valid_left = next((b.left_freq for b in self.binned_data if b.left_freq != 0.0), 400.0)
        last_valid_right = next((b.right_freq for b in self.binned_data if b.right_freq != 0.0), 400.0)
        
        for bin in self.binned_data:
            if bin.left_freq == 0.0:
                bin.left_freq = last_valid_left
            else:
                last_valid_left = bin.left_freq
            if bin.right_freq == 0.0:
                bin.right_freq = last_valid_right
            else:
                last_valid_right = bin.right_freq

def get_percentile_maximum(samples, attr_name, percent):
    """
    Calculate the percentile maximum for a given attribute 
    across left and right channels in a list of Samples.
    """
    left_vals = [getattr(s, f"left_{attr_name}") for s in samples]
    right_vals = [getattr(s, f"right_{attr_name}") for s in samples]
    max_val = np.maximum(np.percentile(left_vals, percent), 
                         np.percentile(right_vals, percent))
    return max_val

def get_maximum(samples, attr_name):
    """
    Calculate the maximum for a given attribute 
    across left and right channels in a list of Samples.
    """
    left_vals = [getattr(s, f"left_{attr_name}") for s in samples]
    right_vals = [getattr(s, f"right_{attr_name}") for s in samples]
    return max(max(left_vals), max(right_vals))
    
def get_minimum(samples, attr_name):
    """
    Calculate the maximum for a given attribute 
    across left and right channels in a list of Samples.
    """
    left_vals = [getattr(s, f"left_{attr_name}") for s in samples]
    right_vals = [getattr(s, f"right_{attr_name}") for s in samples]
    return min(min(left_vals), min(right_vals))

def choose_normalisation_maximum(samples, attr_name):
    """
    Try to figure out a good maximum value for normalisation that ensures
    outlier values won't blow out the scale.
    """
    max_outlier_percent = 2.0 # maximum percentage of outliers in our data
    # "known good" percentile threshold that definitely isn't outlier data
    threshold_percentile = 100.0 - max_outlier_percent
    # How far above our known good maximum we'll allow before classing as outliers
    max_allowed_ratio = 1.1
    # How much to step down the percentile by on each attempt to find a good value
    percentile_step = 0.5
    
    min = get_minimum(samples, attr_name)
    max = get_maximum(samples, attr_name)
    known_good_max = get_percentile_maximum(samples, attr_name, threshold_percentile)
    print(f"{attr_name} min={min:.3f}, max={max:.3f}, {threshold_percentile:.0f}th percentile={known_good_max:.3f}")
    
    percentile = 100.0
    while percentile > threshold_percentile:
        percentile_max = get_percentile_maximum(samples, attr_name, percentile)
        # print(f"  Testing percentile {percentile} max = {percentile_max}")
        if percentile_max <= known_good_max * max_allowed_ratio:
            return percentile_max
        percentile -= 0.5
    return known_good_max * max_allowed_ratio

def normalise_value(value, min_val, max_val):
    """
    Normalise a single value to the range [0.0, 1.0] based on min_val and max_val.
    Clamps values outside the range to 0.0 or 1.0.
    """
    if max_val <= min_val:
        return 0.0  # Avoid division by zero or negative range
    
    normalised = (value - min_val) / (max_val - min_val)
    return max(0.0, min(1.0, normalised))

def normalise_samples(samples, min_amp, max_amp, min_freq, max_freq):
    """
    Normalise amplitude and frequency values of Samples to the range [0.0, 1.0].
    Values are clamped to [0.0, 1.0] if outside the provided ranges.
    """
    return [
        Sample(
            left_freq=normalise_value(s.left_freq, min_freq, max_freq),
            right_freq=normalise_value(s.right_freq, min_freq, max_freq),
            left_amp=normalise_value(s.left_amp, min_amp, max_amp),
            right_amp=normalise_value(s.right_amp, min_amp, max_amp)
        )
        for s in samples
    ]

def get_audio_files(folder_path):
    """
    Return a list of all the supported audio files below a directory (recursive)
    """
    folder = Path(folder_path)
    extensions = ["*.mp3", "*.wav", "*.flac"]
    audio_files = list(
        itertools.chain.from_iterable(
            folder.rglob(pattern) for pattern in extensions
        )
    )
    return audio_files

def write_output_file(destination_filename, samples):
    """
    Write our output HWL file
    An HWL file simply consists of an 8 byte header that always says "YEAHBOI!"
    followed by any number of pulse objects (one for every 1/40th second the file lasts)

    Each pulse object consists of 4x IEEE 754 single precision floating point values in little endian format, as follows: -
    left_channel_amplitude: 0.0 to 1.0
    right_channel_amplitude: 0.0 to 1.0
    left_channel_frequency: 0.0 to 1.0
    right_channel_frequency: 0.0 to 1.0
    """
    if not samples:
        raise ValueError("No samples to write")
    
    with open(destination_filename, 'wb') as file:
       file.write("YEAHBOI!".encode('utf-8'))
       for s in samples:
           file.write(struct.pack('<ffff', s.left_amp, s.right_amp, s.left_freq, s.right_freq))

def convert_audio_file(audio_file, pulses_per_second = 40, pitch_detector_algorithm = "yinfft"):
    """
    Converts a single audio file into an HWL file
    """
    desired_interval = 1.0/pulses_per_second
    window_size = 4096
    hop_size = 128
    discard_low_confidence = False  # Seems to work with "yin", confidence is broken for most other detectors
    confidence_threshold = 0.3
    pitch_detector_tolerance = 0.15
    pitch_detector_silence_threshold = -50.0  # Docs say the Aubio default is -90, actually seems to be -50
    sample_rate = 40960  # Gives exactly 8 hops per bin
    # sample_rate = 44100
    # sample_rate = 96000
    max_freq_lower_limit = 800.0
    nyquist_limit = sample_rate/2.0
    update_every_seconds = 300.0
    src = None
    count_total_hops = 0
    count_pitch_values = 0
    count_zero_values = 0
    count_low_confidence = 0
    count_nyquist = 0

    print(f"\nProcessing {audio_file.name}")
    destination_filename = audio_file.with_suffix('.hwl')
    if destination_filename.exists():
        print(f"Converted file already exists, skipping.")
        return
    try:
        src = source(str(audio_file), sample_rate, hop_size, channels=2)
        print(f"Sample rate={src.samplerate}, Channels={src.channels}, Duration={src.duration}")
        
        pitch_detector_left = pitch(pitch_detector_algorithm, window_size, hop_size, sample_rate)
        pitch_detector_left.set_unit("Hz")
        pitch_detector_left.set_silence(pitch_detector_silence_threshold)
        # pitch_detector_left.set_tolerance(pitch_detector_tolerance)
        pitch_detector_right = pitch(pitch_detector_algorithm, window_size, hop_size, sample_rate)
        pitch_detector_right.set_unit("Hz")
        pitch_detector_right.set_silence(pitch_detector_silence_threshold)
        # pitch_detector_right.set_tolerance(pitch_detector_tolerance)
        
        binner = TimeBinner(desired_interval, sample_rate)
        current_frame = 0
        last_update_time = 0

        print("Detecting frequencies (this may take some time for long files)")
        for frames in src:
            num_frames = len(frames[0])
            if num_frames == 0:
                break
            count_total_hops += 1
            current_time = current_frame / float(sample_rate)
            if current_time - last_update_time > update_every_seconds:
                print(f"  ... still detecting frequencies ({current_time:.0f} seconds processed)")
                last_update_time = current_time
            left_sq = np.sum(frames[0]**2)
            right_sq = np.sum(frames[1]**2)
            
            # Only run pitch detection on full hops
            if(num_frames < hop_size):
                left_pitch = 0.0
                right_pitch = 0.0
            else:
                left_pitch = pitch_detector_left(frames[0])[0]
                right_pitch = pitch_detector_right(frames[1])[0]
                count_pitch_values += 2
                count_zero_values += (left_pitch == 0.0) + (right_pitch == 0.0)
                if discard_low_confidence:
                    # Set any pitch values we aren't confident in to 0.0
                    # Our binner will fill these gaps in later using the last valid value
                    left_confidence = pitch_detector_left.get_confidence()
                    right_confidence = pitch_detector_right.get_confidence()
                    if(left_confidence < confidence_threshold and left_pitch != 0.0):
                        left_pitch = 0.0
                        count_low_confidence += 1
                    if(right_confidence < confidence_threshold and right_pitch != 0.0):
                        right_pitch = 0.0
                        count_low_confidence += 1
                # prune some occasional obviously broken frequency detector results
                if(left_pitch > nyquist_limit):
                   left_pitch = 0.0
                   count_nyquist += 1
                if(right_pitch > nyquist_limit):
                   right_pitch = 0.0
                   count_nyquist += 1
            
            hop_data = HopData(left_pitch, right_pitch, left_sq, right_sq, num_frames)
            binner.add_hop(current_frame, hop_data)
            current_frame += num_frames
        
        binned_samples = binner.finalise_all()
        if not binned_samples:
            print("No data collected, skipping file.")
            return
    
        print(f"Pitch detection stats. Total hops={count_total_hops}, total values={count_pitch_values}, zero values={count_zero_values}, low confidence values={count_low_confidence}, Nyquist limit exceeded={count_nyquist}.")
        print(f"Binned length {len(binned_samples)}")

        max_amp = choose_normalisation_maximum(binned_samples, "amp")
        max_freq = choose_normalisation_maximum(binned_samples, "freq")
        if max_freq < max_freq_lower_limit:
            max_freq = max_freq_lower_limit
        
        print(f"Normalising amplitudes using 0-{max_amp:.3f} range, frequencies using 0-{max_freq:.3f}Hz range.")
        normalised_samples = normalise_samples(binned_samples, 0.0, max_amp, 0.0, max_freq)
        
        print(f"Writing output file {destination_filename}")
        write_output_file(destination_filename, normalised_samples)
    except Exception as e:
        print(f"Error processing {audio_file.name}: {str(e)}")
    finally:
        if src is not None:
            src.close()
        pitch_detector_left = None
        pitch_detector_right = None
        gc.collect()

audio_directory = "audio"
audio_files = get_audio_files(audio_directory)
print("Files to be processed:")
print(audio_files)
for audio_file in audio_files:
    # Currently pulses_per_second must be 40
    # Other pitch detector options like "yin" or "schmitt" may work better or worse
    # depending on the files
    convert_audio_file(audio_file, pulses_per_second = 40, pitch_detector_algorithm = "yinfft")
    gc.collect()