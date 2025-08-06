import xbmc
import xbmcaddon
import xbmcvfs
import os
import json
import urllib.request
import urllib.error
import urllib.parse
import time
import queue
import threading

LOG_TAG = "Howl"
REMOTE_PORT = 4695
MAX_QUEUE_SIZE = 5 # Maximum pending API requests

def log(msg, level=xbmc.LOGINFO):
    xbmc.log(f"[{LOG_TAG}] {msg}", level)
    
class HowlAPI:
    def __init__(self, ip_address):
        self.ip_address = ip_address
        self.port = REMOTE_PORT
        self.timeout = 3
        
        self.request_queue = queue.Queue(maxsize=MAX_QUEUE_SIZE)
        self.callback_queue = queue.Queue()
        self.worker_thread = threading.Thread(target=self._worker)
        self.worker_thread.start()
    
    def update_ip_address(self, new_ip):
        self.ip_address = new_ip
        
    def _enqueue_request(self, endpoint, data, callback, timeout=None):
        """Enqueue API request with optional callback"""
        if timeout is None:
            timeout = self.timeout
            
        try:
            # Try to put the request in the queue without blocking
            self.request_queue.put_nowait((endpoint, data, timeout, callback))
            return True
        except queue.Full:
            log("API request queue is full, dropping request", xbmc.LOGERROR)
            # Immediately notify callback about failure
            if callback is not None:
                try:
                    callback(False)
                except Exception as e:
                    log(f"Error in dropped request callback: {e}", xbmc.LOGERROR)
            return False
    
    def _send_request(self, endpoint, data=None, timeout=None):
        if timeout is None:
            timeout = self.timeout
        url = f"http://{self.ip_address}:{self.port}{endpoint}"
        headers = {'Content-Type': 'application/json'}
        if data is None:
            data = {}
        json_data = json.dumps(data).encode('utf-8')
        req = urllib.request.Request(url, data=json_data, headers=headers, method='POST')
        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                body = response.read()
                if response.status == 200:
                    log(f"API request to {url} succeeded")
                    return True
                else:
                    log(f"API request to {url} failed with status {response.status}", xbmc.LOGERROR)
        except urllib.error.URLError as e:
            log(f"API request to {url} failed: {str(e)}", xbmc.LOGERROR)
        except Exception as e:
            log(f"Unexpected API error: {str(e)}", xbmc.LOGERROR)
        return False
        
    def _worker(self):
        """Worker thread processing API requests"""
        while True:
            item = self.request_queue.get()
            if item is None:
                # Received shutdown signal
                break
                
            try:
                endpoint, data, timeout, callback = item
                
                # Process request
                success = self._send_request(endpoint, data, timeout)
                
                # Queue callback for main thread if provided
                if callback is not None:
                    self.callback_queue.put((callback, success))
                    
            except Exception as e:
                log(f"Worker thread exception: {e}", xbmc.LOGERROR)
                
    def shutdown(self):
        """Shutdown the API worker thread gracefully"""
        # Clear all pending requests
        while not self.request_queue.empty():
            try:
                self.request_queue.get_nowait()
            except queue.Empty:
                break
                
        # Signal worker thread to exit
        self.request_queue.put(None)
        
        # Wait for worker thread to finish
        self.worker_thread.join()
        log("API worker thread stopped")
                
    def fetch_callbacks(self):
        """Fetch all pending callbacks to be processed in main thread"""
        callbacks = []
        while True:
            try:
                callbacks.append(self.callback_queue.get_nowait())
            except queue.Empty:
                break
        return callbacks
        
    def stop_player(self, callback=None):
        return self._enqueue_request("/stop_player", {}, callback)
        
    def start_player(self, from_time=None, callback=None):
        data = {}
        if from_time is not None:
            data["from"] = from_time
        return self._enqueue_request("/start_player", data, callback)
        
    def seek(self, position, callback=None):
        data = {
            "position": position
        }
        return self._enqueue_request("/seek", data, callback)
        
    def load_funscript(self, title, funscript_content, callback=None):
        data = {
            "title": title,
            "funscript": funscript_content
        }
        return self._enqueue_request("/load_funscript", data, callback, timeout=6)

class HowlPlayer(xbmc.Player):
    def __init__(self, api, sync_delay):
        super().__init__()
        self.active = False
        self.paused = False
        self.api = api
        self.sync_delay = sync_delay
        self.sync_requested_time = None  # Monotonic timestamp of last sync request
        self.sync_start_player = False   # Whether to start player with sync
        self.current_video_path = None   # Track current video for callback validation
        
    def clear(self):
        self.active = False
        self.paused = False
        self.sync_requested_time = None
        self.current_video_path = None
    
    def update_sync_delay(self, delay):
        self.sync_delay = delay
        
    def request_sync(self, start_player):
        """Schedule a sync operation after sync_delay"""
        # Kodi takes some time to perform actions like loading a new file or position seeking.
        # We need to wait a while before we can sync the player position, otherwise getTime()
        # can return outdated or inconsistent values.
        self.sync_requested_time = time.monotonic()
        self.sync_start_player = start_player
        
    def check_sync_requested(self):
        """Check if a sync is pending and ready to execute"""
        if self.sync_requested_time is None:
            return False
            
        current_time = time.monotonic()
        elapsed_ms = (current_time - self.sync_requested_time) * 1000
        
        if elapsed_ms >= self.sync_delay:
            start_player = self.sync_start_player
            self.sync_requested_time = None
            self.sync_start_player = False
            self.perform_sync(start_player)
            return True
            
        return False
            
    def perform_sync(self, start_player):
        """Execute the sync operation with current player time"""
        if not self.active:
            log("Sync skipped: Player inactive", xbmc.LOGDEBUG)
            return False
            
        try:
            current_time = self.getTime()
            if start_player:
                return self.api.start_player(current_time)
            else:
                return self.api.seek(current_time)
        except Exception as e:
            log(f"Sync failed: {str(e)}", xbmc.LOGERROR)
            return False
        
    def load_funscript(self, funscript_path, video_path):
        try:
            with xbmcvfs.File(funscript_path, 'r') as f:
                funscript_content = f.read()
            base_name = os.path.basename(funscript_path)
            title = os.path.splitext(base_name)[0]
            
            callback = lambda success: self.funscript_loaded_callback(video_path, success)
            # Send async request with callback
            queued = self.api.load_funscript(title, funscript_content, callback=callback)
            if not queued:
                log("Failed to queue funscript load request", xbmc.LOGERROR)
        except Exception as e:
            log(f"load_funscript crashed: {str(e)}", xbmc.LOGERROR)
            
    def funscript_loaded_callback(self, video_path, success):
        # Only activate if we're still playing the same video
        if self.current_video_path == video_path:
            if success:
                log(f"Funscript loaded successfully for {video_path}")
                self.active = True
                self.request_sync(start_player=True)
            else:
                log(f"Funscript loading failed for {video_path}", xbmc.LOGERROR)
                self.active = False
        else:
            log(f"Ignoring funscript callback for old video: {video_path}", xbmc.LOGDEBUG)
            
    def onAVStarted(self):
        self.clear()
        try:
            if not self.isPlayingVideo():
                return
            video_path = self.getPlayingFile()
            self.current_video_path = video_path
            base, _ = os.path.splitext(video_path)
            funscript_path = base + ".funscript"
            if any(video_path.startswith(proto) for proto in ('pvr://', 'dvd://', 'bluray://')):
                return
            if xbmcvfs.exists(funscript_path):
                log(f"Funscript found for {video_path}")
                self.load_funscript(funscript_path, video_path)
            else:
                log(f"No matching funscript for {video_path}")
                return
        except Exception as e:
            log(f"onAVStarted crashed: {str(e)}", xbmc.LOGERROR)
    
    def stopped(self):
        active = self.active
        self.clear()
        if active:
            self.api.stop_player()
    
    def onPlayBackStopped(self):
        self.stopped()
            
    def onPlayBackEnded(self):
        self.stopped()
            
    def onPlayBackPaused(self):
        if not self.active:
            return
        self.paused = True
        self.sync_requested_time = None
        self.api.stop_player()
            
    def onPlayBackResumed(self):
        if not self.active:
            return
        self.paused = False
        self.request_sync(start_player=True)
            
    def onPlayBackSeek(self, time, seekOffset):
        if not self.active or self.paused:
            # no need to do anything if we're paused as we will sync playback on resume
            return
        self.request_sync(start_player=False)
        
    def onPlayBackSeekChapter(self, chapter):
        if not self.active or self.paused:
            return
        self.request_sync(start_player=False)
    
class HowlService(xbmc.Monitor):
    def __init__(self):
        super().__init__()
        self._get_settings()
        self.api = HowlAPI(self.ip_address)
        self.player = HowlPlayer(self.api, self.sync_delay)
        log("Service started")
    
    def _get_settings(self):
        addon = xbmcaddon.Addon()
        self.ip_address = addon.getSettingString("ip_address")
        # Get sync_delay as integer (default to 500 if conversion fails)
        try:
            self.sync_delay = int(addon.getSetting("sync_delay"))
        except (TypeError, ValueError):
            self.sync_delay = 500
            log(f"Using default sync_delay: {self.sync_delay}", xbmc.LOGWARNING)
        
    def onSettingsChanged(self):
        old_ip = self.ip_address
        old_delay = self.sync_delay
        self._get_settings()
        
        if self.ip_address != old_ip:
            self.api.update_ip_address(self.ip_address)
            log(f"Updated remote IP to: {self.ip_address}")
            
        if self.sync_delay != old_delay:
            self.player.update_sync_delay(self.sync_delay)
            log(f"Updated sync_delay to: {self.sync_delay}ms")
            
    def run(self):
        """Main service loop with periodic sync checks"""
        while not self.abortRequested():
            # Check for sync requests
            self.player.check_sync_requested()
            
            for callback, result in self.api.fetch_callbacks():
                try:
                    callback(result)
                except Exception as e:
                    log(f"Error in API callback: {str(e)}", xbmc.LOGERROR)
            
            # Check for abort every 100ms
            if self.waitForAbort(0.1):
                break
        # Service is stopping - shutdown API worker thread
        self.api.shutdown()
    
if __name__ == '__main__':
    service = HowlService()
    service.run()
    log("Service stopped")