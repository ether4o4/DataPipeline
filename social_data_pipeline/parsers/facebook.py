from .generic import GenericJsonParser


class FacebookParser(GenericJsonParser):
    platform = "facebook"
    marker_names = ("messages", "messages.json", "your_facebook_activity/messages", "your_facebook_activity")
