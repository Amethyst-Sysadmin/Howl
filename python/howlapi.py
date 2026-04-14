import base64
import json
import urllib.request
import urllib.error
from dataclasses import dataclass
from typing import Optional, Dict, Any, List


class HowlAPIError(Exception):
    """
    Exception raised when the Howl API returns an HTTP error (400-599).
    """
    def __init__(self, status_code: int, message: str):
        self.status_code = status_code
        self.message = message
        super().__init__(f"API Error {status_code}: {message}")


@dataclass
class MainOptionsStatus:
    """Represents the options state of the Howl app."""
    power_a: int
    power_b: int
    power_a_limit: int
    power_b_limit: int
    mute: bool
    auto_increase_power: bool
    swap_channels: bool
    freq_range_min: float
    freq_range_max: float


@dataclass
class PlayerStatus:
    """Represents the player state of the Howl app."""
    playing: bool
    position: float
    title: str
    duration: float


@dataclass
class StatusResponse:
    """Standard response returned by most successful API calls."""
    options: MainOptionsStatus
    player: PlayerStatus


@dataclass
class Activity:
    """Represents an available built-in activity."""
    name: str
    display_name: str


class HowlAPI:
    """
    Reference implementation of the Howl Remote API for Python.

    Provides synchronous methods to control a remote Howl device.
    """

    def __init__(self, ip_address: str, api_key: str):
        """
        Initialize the HowlAPI client.

        :param ip_address: The IP address of the remote Howl device.
        :param api_key: The alphanumeric API key for authentication.
        """
        self.base_url = f"http://{ip_address}:4695"
        self.api_key = api_key
        self.timeout = 5

        self._headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json"
        }

    def _parse_status_response(self, json_data: Dict[str, Any]) -> StatusResponse:
        """Parses the raw JSON dictionary into StatusResponse dataclasses."""
        opts_data = json_data.get("options", {})
        player_data = json_data.get("player", {})

        options = MainOptionsStatus(
            power_a=opts_data.get("power_a"),
            power_b=opts_data.get("power_b"),
            power_a_limit=opts_data.get("power_a_limit"),
            power_b_limit=opts_data.get("power_b_limit"),
            mute=opts_data.get("mute"),
            auto_increase_power=opts_data.get("auto_increase_power"),
            swap_channels=opts_data.get("swap_channels"),
            freq_range_min=opts_data.get("freq_range_min"),
            freq_range_max=opts_data.get("freq_range_max")
        )

        player = PlayerStatus(
            playing=player_data.get("playing"),
            position=player_data.get("position"),
            title=player_data.get("title"),
            duration=player_data.get("duration")
        )

        return StatusResponse(options=options, player=player)

    def _request(self, endpoint: str, payload: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Internal helper to make a POST request and return the raw JSON response.

        :param endpoint: The API endpoint path (e.g., 'start_player').
        :param payload: Optional dictionary to send as the JSON body.
        :return: Raw JSON dictionary on success.
        :raises HowlAPIError: On HTTP 400-599 responses.
        :raises TimeoutError: On request timeout (5 seconds).
        :raises urllib.error.URLError: On network connectivity issues.
        """
        url = f"{self.base_url}/{endpoint}"

        json_body = payload if payload is not None else {}
        data = json.dumps(json_body).encode('utf-8')

        req = urllib.request.Request(url, data=data, headers=self._headers, method="POST")

        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as response:
                response_data = response.read().decode('utf-8')
                return json.loads(response_data)
        except urllib.error.HTTPError as e:
            body = e.read().decode('utf-8')
            error_message = body
            try:
                error_data = json.loads(body)
                if "error" in error_data and "message" in error_data["error"]:
                    error_message = error_data["error"]["message"]
            except (ValueError, json.JSONDecodeError):
                # Error response did not have the expected JSON format
                pass

            raise HowlAPIError(e.code, error_message)

    def _post(self, endpoint: str, payload: Optional[Dict[str, Any]] = None) -> StatusResponse:
        """
        Internal helper to make a POST request and return a parsed StatusResponse.

        :param endpoint: The API endpoint path (e.g., 'start_player').
        :param payload: Optional dictionary to send as the JSON body.
        :return: StatusResponse object on success.
        :raises HowlAPIError: On HTTP 400-599 responses.
        :raises TimeoutError: On request timeout (5 seconds).
        :raises urllib.error.URLError: On network connectivity issues.
        """
        return self._parse_status_response(self._request(endpoint, payload))

    def get_status(self) -> StatusResponse:
        """
        Retrieve the current status of the Howl device.
        Corresponds to POST /status.
        """
        return self._post("status")

    def start_player(self, from_pos: Optional[float] = None) -> StatusResponse:
        """
        Start or resume playback of the current source.
        Corresponds to POST /start_player.

        :param from_pos: Optional starting position in seconds.
        """
        payload = {}
        if from_pos is not None:
            payload["from"] = from_pos
        return self._post("start_player", payload)

    def seek(self, position: float) -> StatusResponse:
        """
        Seek to a specific timestamp in the current playback source.
        Corresponds to POST /seek.

        :param position: Position in seconds.
        """
        return self._post("seek", {"position": position})

    def stop_player(self) -> StatusResponse:
        """
        Stop or pause playback.
        Corresponds to POST /stop_player.
        """
        return self._post("stop_player")

    def load_funscript(self, funscript: str, title: Optional[str] = None,
                       loop: Optional[bool] = None, play: Optional[bool] = None) -> StatusResponse:
        """
        Load a funscript into the player.
        Corresponds to POST /load_funscript.

        :param funscript: The complete JSON content of the Funscript file as a string.
        :param title: Optional display title for the script.
        :param loop: Optional; automatically loop when reaching the end (default false).
        :param play: Optional; true to start playback immediately (default false).
        """
        payload = {"funscript": funscript}
        if title is not None:
            payload["title"] = title
        if loop is not None:
            payload["loop"] = loop
        if play is not None:
            payload["play"] = play
        return self._post("load_funscript", payload)

    def load_hwl(self, hwl: bytes, title: Optional[str] = None,
                 loop: Optional[bool] = None, play: Optional[bool] = None) -> StatusResponse:
        """
        Load a HWL file into the player.
        Corresponds to POST /load_hwl.

        :param hwl: The raw binary content of the HWL file.
        :param title: Optional display title for the file.
        :param loop: Optional; automatically loop when reaching the end (default true).
        :param play: Optional; true to start playback immediately (default false).
        """
        hwl_base64 = base64.b64encode(hwl).decode('utf-8')
        payload = {"hwl": hwl_base64}
        if title is not None:
            payload["title"] = title
        if loop is not None:
            payload["loop"] = loop
        if play is not None:
            payload["play"] = play
        return self._post("load_hwl", payload)

    def load_activity(self, name: str, play: Optional[bool] = None) -> StatusResponse:
        """
        Load one of Howl's built-in activities.
        Corresponds to POST /load_activity.

        :param name: The internal name of the activity to load (e.g. "CALIBRATION1" or "CHAOS").
        :param play: Optional; true to start playback immediately (default false).
        """
        payload = {"name": name}
        if play is not None:
            payload["play"] = play
        return self._post("load_activity", payload)

    def available_activities(self) -> List[Activity]:
        """
        Retrieve a list of all available built-in activities.
        Corresponds to POST /available_activities.

        :return: List of Activity objects.
        """
        raw = self._request("available_activities")
        activities_data = raw.get("activities", [])
        return [
            Activity(name=a.get("name"), display_name=a.get("display_name"))
            for a in activities_data
        ]

    def set_power(self, power_a: Optional[int] = None, power_b: Optional[int] = None) -> StatusResponse:
        """
        Set the absolute power level for one or both channels.
        Corresponds to POST /set_power.

        :param power_a: The desired power level for channel A (0-200).
        :param power_b: The desired power level for channel B (0-200).
        """
        payload = {}
        if power_a is not None:
            payload["power_a"] = power_a
        if power_b is not None:
            payload["power_b"] = power_b
        return self._post("set_power", payload)

    def increment_power(self, channel: int, step: int = 0) -> StatusResponse:
        """
        Increase the power level on one or both channels by a specified amount.
        Corresponds to POST /increment_power.

        :param channel: The channel identifier (0 for Channel A, 1 for Channel B, -1 for both channels).
        :param step: The amount to increase the power by. Set to 0 to use the step size
                     configured in Howl's settings (default 0).
        """
        return self._post("increment_power", {
            "channel": channel,
            "step": step
        })

    def decrement_power(self, channel: int, step: int = 0) -> StatusResponse:
        """
        Decrease the power level on one or both channels by a specified amount.
        Corresponds to POST /decrement_power.

        :param channel: The channel identifier (0 for Channel A, 1 for Channel B, -1 for both channels).
        :param step: The amount to decrease the power by. Set to 0 to use the step size
                     configured in Howl's settings (default 0).
        """
        return self._post("decrement_power", {
            "channel": channel,
            "step": step
        })

    def set_mute(self, value: Optional[bool] = None) -> StatusResponse:
        """
        Set or toggle the global mute state.
        Corresponds to POST /set_mute.

        :param value: True to mute, False to unmute. If omitted, the current mute state will be toggled.
        """
        payload = {}
        if value is not None:
            payload["value"] = value
        return self._post("set_mute", payload)

    def set_swap_channels(self, value: Optional[bool] = None) -> StatusResponse:
        """
        Set or toggle the swap channels feature.
        Corresponds to POST /set_swap_channels.

        :param value: True to enable swap channels, False to disable. If omitted, the current state will be toggled.
        """
        payload = {}
        if value is not None:
            payload["value"] = value
        return self._post("set_swap_channels", payload)

    def set_auto_increase(self, value: Optional[bool] = None) -> StatusResponse:
        """
        Set or toggle the auto increase power feature.
        Corresponds to POST /set_auto_increase.

        :param value: True to enable auto increase, False to disable. If omitted, the current state will be toggled.
        """
        payload = {}
        if value is not None:
            payload["value"] = value
        return self._post("set_auto_increase", payload)

    def set_freq_range(self, min: float, max: float) -> StatusResponse:
        """
        Set the frequency range subset for playback.
        Corresponds to POST /set_freq_range.

        :param min: The minimum frequency value (0.0 to 1.0).
        :param max: The maximum frequency value (0.0 to 1.0). Must be greater than min,
                    with a minimum difference of 0.01.
        """
        return self._post("set_freq_range", {"min": min, "max": max})