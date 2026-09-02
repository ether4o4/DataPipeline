from .generic import GenericJsonParser


class InstagramParser(GenericJsonParser):
    platform = "instagram"
    marker_names = ("messages", "messages.json", "your_instagram_activity", "connections")
