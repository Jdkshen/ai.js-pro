#include <cstdio>
#include "imgui_font.h"

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    // Reproduce the ROM font failure without GL. Release builds disable ImGui assertions.
    ImGui::CreateContext();
    ImGuiIO& rom = ImGui::GetIO();
    rom.Fonts->AddFontDefault();
    ImFontConfig merge; merge.MergeMode = true;
    rom.Fonts->AddFontFromFileTTF(argv[1], 39, &merge, rom.Fonts->GetGlyphRangesChineseSimplifiedCommon());
    bool romOk = rom.Fonts->Build();
    std::printf("ROM font atlas built=%d loaded=%d\n", romOk, rom.Fonts->Fonts[0]->IsLoaded());
    ImGui::DestroyContext();
    for (int i = 1; i <= 2; i++) {
        ImGui::CreateContext(); ImGuiIO& io = ImGui::GetIO(); bool fallback = false;
        if (!buildImGuiFonts(io, argv[i], 39, &fallback)) return 3;
        if (i == 2 && (fallback || !io.Fonts->Fonts[0]->FindGlyphNoFallback(0x68CB)
                || !io.Fonts->Fonts[0]->FindGlyphNoFallback(0x94AE))) return 4;
        io.DisplaySize = ImVec2(1280, 2772); io.DeltaTime = 0.016f;
        ImGui::NewFrame(); ImGui::Begin("checked font"); ImGui::TextUnformatted("first frame"); ImGui::End(); ImGui::Render();
        std::printf("CHECKED font=%d fallback=%d first frame OK\n", i, fallback);
        ImGui::DestroyContext();
    }
    return 0;
}
