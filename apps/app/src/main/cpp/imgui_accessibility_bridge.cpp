#include "imgui_accessibility_bridge.h"

#include <algorithm>

namespace {

bool nodesEqual(const ImGuiAccessibilityNode &left, const ImGuiAccessibilityNode &right) {
    return left.id == right.id &&
           left.left == right.left && left.top == right.top &&
           left.right == right.right && left.bottom == right.bottom &&
           left.role == right.role && left.flags == right.flags &&
           left.text == right.text && left.actionKind == right.actionKind &&
           left.actionValue == right.actionValue && left.targetIndex == right.targetIndex;
}

bool snapshotsEqual(const std::vector<ImGuiAccessibilityNode> &left,
                    const std::vector<ImGuiAccessibilityNode> &right) {
    if (left.size() != right.size()) return false;
    for (size_t index = 0; index < left.size(); ++index) {
        if (!nodesEqual(left[index], right[index])) return false;
    }
    return true;
}

}  // namespace

void ImGuiAccessibilityBridge::beginFrame() {
    building_.clear();
}

void ImGuiAccessibilityBridge::addNode(const ImGuiAccessibilityNode &node) {
    if (node.id <= 0 || node.right <= node.left || node.bottom <= node.top || node.text.empty()) {
        return;
    }
    const auto duplicate = std::find_if(building_.begin(), building_.end(),
                                        [&](const ImGuiAccessibilityNode &entry) {
                                            return entry.id == node.id;
                                        });
    if (duplicate == building_.end()) building_.push_back(node);
}

void ImGuiAccessibilityBridge::finishFrame() {
    if (!snapshotsEqual(building_, snapshot_)) {
        snapshot_ = building_;
        ++revision_;
        if (revision_ <= 0) revision_ = 1;
    }
}

int ImGuiAccessibilityBridge::revision() const {
    return revision_;
}

const std::vector<ImGuiAccessibilityNode> &ImGuiAccessibilityBridge::nodes() const {
    return snapshot_;
}

const ImGuiAccessibilityNode *ImGuiAccessibilityBridge::findNode(int id) const {
    const auto result = std::find_if(snapshot_.begin(), snapshot_.end(),
                                     [&](const ImGuiAccessibilityNode &node) {
                                         return node.id == id;
                                     });
    return result == snapshot_.end() ? nullptr : &*result;
}

int stableAccessibilityId(const std::string &key, int salt) {
    // FNV-1a; keep dynamic ids in a range separate from the small static toolbar ids.
    uint32_t hash = 2166136261u ^ static_cast<uint32_t>(salt * 16777619u);
    for (unsigned char byte : key) {
        hash ^= byte;
        hash *= 16777619u;
    }
    return static_cast<int>(0x10000000u | (hash & 0x0fffffffu));
}

