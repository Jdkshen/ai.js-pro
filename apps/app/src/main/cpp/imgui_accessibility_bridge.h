#pragma once

#include <cstdint>
#include <string>
#include <vector>

enum class ImGuiAccessibilityRole : int {
    Text = 0,
    Button = 1,
    List = 2,
    ListItem = 3,
    Tab = 4,
};

enum ImGuiAccessibilityFlags : int {
    ImGuiAccessibilityNone = 0,
    ImGuiAccessibilityClickable = 1 << 0,
    ImGuiAccessibilityScrollable = 1 << 1,
    ImGuiAccessibilitySelected = 1 << 2,
};

enum class ImGuiAccessibilityActionKind : int {
    None = 0,
    QueueAction = 1,
    SelectScript = 2,
    SelectBreadcrumb = 3,
    SelectSection = 4,
    ScrollScripts = 5,
    ToggleCreateMenu = 6,
};

struct ImGuiAccessibilityNode {
    int id = 0;
    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;
    ImGuiAccessibilityRole role = ImGuiAccessibilityRole::Text;
    int flags = ImGuiAccessibilityNone;
    std::string text;
    ImGuiAccessibilityActionKind actionKind = ImGuiAccessibilityActionKind::None;
    int actionValue = 0;
    int targetIndex = -1;
};

/**
 * Double-buffered semantic snapshot for a Dear ImGui frame.
 *
 * The render thread builds one immutable snapshot. Android's accessibility thread only reads the
 * last completed snapshot, so it never walks ImGui state while that state is being mutated.
 */
class ImGuiAccessibilityBridge {
public:
    void beginFrame();
    void addNode(const ImGuiAccessibilityNode &node);
    void finishFrame();

    int revision() const;
    const std::vector<ImGuiAccessibilityNode> &nodes() const;
    const ImGuiAccessibilityNode *findNode(int id) const;

private:
    std::vector<ImGuiAccessibilityNode> building_;
    std::vector<ImGuiAccessibilityNode> snapshot_;
    int revision_ = 0;
};

// Produces a stable positive virtual-view id from a persistent key such as a canonical file path.
int stableAccessibilityId(const std::string &key, int salt);
