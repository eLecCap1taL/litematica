package fi.dy.masa.litematica.schematic.verifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.infohud.IInfoHudRenderer;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.render.infohud.RenderPhase;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.option.ColorConfig;
import fi.dy.masa.malilib.listener.TaskCompletionListener;
import fi.dy.masa.malilib.overlay.message.MessageDispatcher;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import fi.dy.masa.malilib.util.position.LayerRange;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

public class SchematicVerifier extends TaskBase implements IInfoHudRenderer
{
    private static final MutablePair<IBlockState, IBlockState> MUTABLE_PAIR = new MutablePair<>();
    private static final BlockPos.MutableBlockPos MUTABLE_POS = new BlockPos.MutableBlockPos();
    private static final IBlockState AIR = Blocks.AIR.getDefaultState();
    private static final List<SchematicVerifier> ACTIVE_VERIFIERS = new ArrayList<>();

    private final ArrayListMultimap<Pair<IBlockState, IBlockState>, BlockPos> missingBlocksPositions = ArrayListMultimap.create();
    private final ArrayListMultimap<Pair<IBlockState, IBlockState>, BlockPos> extraBlocksPositions = ArrayListMultimap.create();
    private final ArrayListMultimap<Pair<IBlockState, IBlockState>, BlockPos> wrongBlocksPositions = ArrayListMultimap.create();
    private final ArrayListMultimap<Pair<IBlockState, IBlockState>, BlockPos> wrongStatesPositions = ArrayListMultimap.create();
    private final Object2IntOpenHashMap<IBlockState> correctStateCounts = new Object2IntOpenHashMap<>();
    private final Object2ObjectOpenHashMap<BlockPos, BlockMismatch> blockMismatches = new Object2ObjectOpenHashMap<>();
    private final HashSet<Pair<IBlockState, IBlockState>> ignoredMismatches = new HashSet<>();
    private final List<BlockPos> missingBlocksPositionsClosest = new ArrayList<>();
    private final List<BlockPos> extraBlocksPositionsClosest = new ArrayList<>();
    private final List<BlockPos> mismatchedBlocksPositionsClosest = new ArrayList<>();
    private final List<BlockPos> mismatchedStatesPositionsClosest = new ArrayList<>();
    private final Set<MismatchType> selectedCategories = new HashSet<>();
    private final HashMultimap<MismatchType, BlockMismatch> selectedEntries = HashMultimap.create();
    private final Set<ChunkPos> requiredChunks = new HashSet<>();
    private final Set<BlockPos> recheckQueue = new HashSet<>();
    private WorldClient worldClient;
    private WorldSchematic worldSchematic;
    private SchematicPlacement schematicPlacement;
    private final List<MismatchRenderPos> mismatchPositionsForRender = new ArrayList<>();
    private final List<BlockPos> mismatchBlockPositionsForRender = new ArrayList<>();
    private SortCriteria sortCriteria = SortCriteria.NAME_EXPECTED;
    private boolean sortReverse;
    private boolean verificationStarted;
    private boolean verificationActive;
    private boolean shouldRenderInfoHud = true;
    private int totalRequiredChunks;
    private int schematicBlocks;
    private int clientBlocks;
    private int correctStatesCount;

    public SchematicVerifier()
    {
        this.name = StringUtils.translate("litematica.gui.label.schematic_verifier.verifier");
    }

    public static void clearActiveVerifiers()
    {
        ACTIVE_VERIFIERS.clear();
    }

    public static void markVerifierBlockChanges(BlockPos pos)
    {
        for (SchematicVerifier activeVerifier : ACTIVE_VERIFIERS)
        {
            activeVerifier.markBlockChanged(pos);
        }
    }

    public static List<SchematicVerifier> getActiveVerifiers()
    {
        return ACTIVE_VERIFIERS;
    }

    @Override
    public boolean getShouldRenderText(RenderPhase phase)
    {
        return this.shouldRenderInfoHud && phase == RenderPhase.POST &&
               Configs.InfoOverlays.VERIFIER_OVERLAY_RENDERING.getBooleanValue();
    }

    public void toggleShouldRenderInfoHUD()
    {
        this.shouldRenderInfoHud = ! this.shouldRenderInfoHud;
    }

    public boolean isActive()
    {
        return this.verificationActive;
    }

    public boolean isPaused()
    {
        return this.verificationStarted && this.verificationActive == false && this.finished == false;
    }

    public boolean isFinished()
    {
        return this.finished;
    }

    public int getTotalChunks()
    {
        return this.totalRequiredChunks;
    }

    public int getUnseenChunks()
    {
        return this.requiredChunks.size();
    }

    public int getSchematicTotalBlocks()
    {
        return this.schematicBlocks;
    }

    public int getRealWorldTotalBlocks()
    {
        return this.clientBlocks;
    }

    public int getMissingBlocks()
    {
        return this.missingBlocksPositions.size();
    }

    public int getExtraBlocks()
    {
        return this.extraBlocksPositions.size();
    }

    public int getMismatchedBlocks()
    {
        return this.wrongBlocksPositions.size();
    }

    public int getMismatchedStates()
    {
        return this.wrongStatesPositions.size();
    }

    public int getCorrectStatesCount()
    {
        return this.correctStatesCount;
    }

    public int getTotalErrors()
    {
        return this.getMismatchedBlocks() +
                this.getMismatchedStates() +
                this.getExtraBlocks() +
                this.getMissingBlocks();
    }

    public SortCriteria getSortCriteria()
    {
        return this.sortCriteria;
    }

    public boolean getSortInReverse()
    {
        return this.sortReverse;
    }

    public void setSortCriteria(SortCriteria criteria)
    {
        if (this.sortCriteria == criteria)
        {
            this.sortReverse = ! this.sortReverse;
        }
        else
        {
            this.sortCriteria = criteria;
            this.sortReverse = criteria != SortCriteria.COUNT;
        }
    }

    public void toggleMismatchCategorySelected(MismatchType type)
    {
        if (type == MismatchType.CORRECT_STATE)
        {
            return;
        }

        if (this.selectedCategories.contains(type))
        {
            this.selectedCategories.remove(type);
        }
        else
        {
            this.selectedCategories.add(type);

            // Remove any existing selected individual entries within this category
            this.removeSelectedEntriesOfType(type);
        }

        this.updateMismatchOverlays();
    }

    public void toggleMismatchEntrySelected(BlockMismatch mismatch)
    {
        MismatchType type = mismatch.mismatchType;

        if (this.selectedEntries.containsValue(mismatch))
        {
            this.selectedEntries.remove(type, mismatch);
            this.updateMismatchOverlays();
        }
        else
        {
            this.selectedCategories.remove(type);
            this.selectedEntries.put(type, mismatch);
            this.updateMismatchOverlays();
        }
    }

    private void removeSelectedEntriesOfType(MismatchType type)
    {
        this.selectedEntries.removeAll(type);
    }

    public boolean isMismatchCategorySelected(MismatchType type)
    {
        return this.selectedCategories.contains(type);
    }

    public boolean isMismatchEntrySelected(BlockMismatch mismatch)
    {
        return this.selectedEntries.containsValue(mismatch);
    }

    private void clearActiveMismatchRenderPositions()
    {
        this.mismatchPositionsForRender.clear();
        this.mismatchBlockPositionsForRender.clear();
        this.infoHudLines.clear();
    }

    public List<MismatchRenderPos> getSelectedMismatchPositionsForRender()
    {
        return this.mismatchPositionsForRender;
    }

    public List<BlockPos> getSelectedMismatchBlockPositionsForRender()
    {
        return this.mismatchBlockPositionsForRender;
    }

    @Override
    public boolean canExecute()
    {
        return Minecraft.getMinecraft().world != null;
    }

    @Override
    public boolean shouldRemove()
    {
        return this.canExecute() == false;
    }

    @Override
    public boolean execute()
    {
        this.verifyChunks();
        this.checkChangedPositions();
        return false;
    }

    @Override
    public void stop()
    {
        // Don't call notifyListeners
    }

    public void startVerification(WorldClient worldClient, WorldSchematic worldSchematic,
            SchematicPlacement schematicPlacement, TaskCompletionListener completionListener)
    {
        this.reset();

        this.worldClient = worldClient;
        this.worldSchematic = worldSchematic;
        this.schematicPlacement = schematicPlacement;

        this.setCompletionListener(completionListener);
        this.requiredChunks.addAll(schematicPlacement.getTouchedChunks());
        this.totalRequiredChunks = this.requiredChunks.size();
        this.verificationStarted = true;

        TaskScheduler.getInstanceClient().scheduleTask(this, 10);
        InfoHud.getInstance().addInfoHudRenderer(this, true);
        ACTIVE_VERIFIERS.add(this);

        this.verificationActive = true;

        this.updateRequiredChunksStringList();
    }

    public void resume()
    {
        if (this.verificationStarted)
        {
            this.verificationActive = true;
            this.updateRequiredChunksStringList();
        }
    }

    public void stopVerification()
    {
        this.verificationActive = false;
    }

    public void reset()
    {
        this.stopVerification();
        this.clearReferences();
        this.clearData();
    }

    private void clearReferences()
    {
        this.worldClient = null;
        this.worldSchematic = null;
        this.schematicPlacement = null;
    }

    private void clearData()
    {
        this.verificationActive = false;
        this.verificationStarted = false;
        this.finished = false;
        this.totalRequiredChunks = 0;
        this.correctStatesCount = 0;
        this.schematicBlocks = 0;
        this.clientBlocks = 0;
        this.requiredChunks.clear();
        this.recheckQueue.clear();

        this.missingBlocksPositions.clear();
        this.extraBlocksPositions.clear();
        this.wrongBlocksPositions.clear();
        this.wrongStatesPositions.clear();
        this.blockMismatches.clear();
        this.correctStateCounts.clear();
        this.selectedCategories.clear();
        this.selectedEntries.clear();
        this.mismatchBlockPositionsForRender.clear();
        this.mismatchPositionsForRender.clear();

        ACTIVE_VERIFIERS.remove(this);
        TaskScheduler.getInstanceClient().removeTask(this);

        InfoHud.getInstance().removeInfoHudRenderer(this, false);
        this.clearActiveMismatchRenderPositions();
    }

    public void markBlockChanged(BlockPos pos)
    {
        if (this.finished)
        {
            BlockMismatch mismatch = this.blockMismatches.get(pos);

            if (mismatch != null)
            {
                this.recheckQueue.add(pos);
            }
        }
    }

    private void checkChangedPositions()
    {
        if (this.finished && this.recheckQueue.isEmpty() == false)
        {
            Iterator<BlockPos> iter = this.recheckQueue.iterator();

            while (iter.hasNext())
            {
                BlockPos pos = iter.next();

                if (this.worldClient.isAreaLoaded(pos, 1, false) &&
                    this.worldSchematic.isBlockLoaded(pos, false))
                {
                    BlockMismatch mismatch = this.blockMismatches.get(pos);

                    if (mismatch != null)
                    {
                        this.blockMismatches.remove(pos);

                        IBlockState stateFound = this.worldClient.getBlockState(pos).getActualState(this.worldClient, pos);
                        MUTABLE_PAIR.setLeft(mismatch.stateExpected);
                        MUTABLE_PAIR.setRight(mismatch.stateFound);

                        this.getMapForMismatchType(mismatch.mismatchType).remove(MUTABLE_PAIR, pos);
                        this.checkBlockStates(pos.getX(), pos.getY(), pos.getZ(), mismatch.stateExpected, stateFound);

                        if (stateFound != AIR && mismatch.stateFound == AIR)
                        {
                            this.clientBlocks++;
                        }
                    }
                    else
                    {
                        IBlockState stateExpected = this.worldSchematic.getBlockState(pos);
                        IBlockState stateFound = this.worldClient.getBlockState(pos).getActualState(this.worldClient, pos);
                        this.checkBlockStates(pos.getX(), pos.getY(), pos.getZ(), stateExpected, stateFound);
                    }

                    iter.remove();
                }
            }

            if (this.recheckQueue.isEmpty())
            {
                this.updateMismatchOverlays();
            }
        }
    }

    private ArrayListMultimap<Pair<IBlockState, IBlockState>, BlockPos> getMapForMismatchType(MismatchType mismatchType)
    {
        switch (mismatchType)
        {
            case MISSING:
                return this.missingBlocksPositions;
            case EXTRA:
                return this.extraBlocksPositions;
            case WRONG_BLOCK:
                return this.wrongBlocksPositions;
            case WRONG_STATE:
                return this.wrongStatesPositions;
            default:
                return null;
        }
    }

    private boolean verifyChunks()
    {
        if (this.verificationActive)
        {
            Iterator<ChunkPos> iter = this.requiredChunks.iterator();
            boolean checkedSome = false;

            while (iter.hasNext())
            {
                if ((System.nanoTime() - DataManager.getClientTickStartTime()) >= 50000000L)
                {
                    break;
                }

                ChunkPos pos = iter.next();
                int count = 0;

                for (int cx = pos.x - 1; cx <= pos.x + 1; ++cx)
                {
                    for (int cz = pos.z - 1; cz <= pos.z + 1; ++cz)
                    {
                        if (this.worldClient.getChunkProvider().isChunkGeneratedAt(cx, cz))
                        {
                            ++count;
                        }
                    }
                }

                // Require the surrounding chunks in the client world to be loaded as well
                if (count == 9 && this.worldSchematic.getChunkProvider().isChunkGeneratedAt(pos.x, pos.z))
                {
                    Chunk chunkClient = this.worldClient.getChunk(pos.x, pos.z);
                    Chunk chunkSchematic = this.worldSchematic.getChunk(pos.x, pos.z);
                    Map<String, IntBoundingBox> boxes = this.schematicPlacement.getBoxesWithinChunk(pos.x, pos.z);

                    for (IntBoundingBox box : boxes.values())
                    {
                        this.verifyChunk(chunkClient, chunkSchematic, box);
                    }

                    iter.remove();
                    checkedSome = true;
                }
            }

            if (checkedSome)
            {
                this.updateRequiredChunksStringList();
            }

            if (this.requiredChunks.isEmpty())
            {
                this.verificationActive = false;
                this.verificationStarted = false;
                this.finished = true;

                this.notifyListener();
            }
        }

        return this.verificationActive == false; // finished or stopped
    }

    public void ignoreStateMismatch(BlockMismatch mismatch)
    {
        this.ignoreStateMismatch(mismatch, true);
    }

    private void ignoreStateMismatch(BlockMismatch mismatch, boolean updateOverlay)
    {
        Pair<IBlockState, IBlockState> ignore = Pair.of(mismatch.stateExpected, mismatch.stateFound);

        if (this.ignoredMismatches.contains(ignore) == false)
        {
            this.ignoredMismatches.add(ignore);
            this.getMapForMismatchType(mismatch.mismatchType).removeAll(ignore);
            this.blockMismatches.entrySet().removeIf(entry -> entry.getValue().equals(mismatch));
        }

        if (updateOverlay)
        {
            this.updateMismatchOverlays();
        }
    }

    public void addIgnoredStateMismatches(Collection<BlockMismatch> ignore)
    {
        for (BlockMismatch mismatch : ignore)
        {
            this.ignoreStateMismatch(mismatch, false);
        }

        this.updateMismatchOverlays();
    }

    public void resetIgnoredStateMismatches()
    {
        this.ignoredMismatches.clear();
    }

    public Set<Pair<IBlockState, IBlockState>> getIgnoredMismatches()
    {
        return this.ignoredMismatches;
    }

    public Object2IntOpenHashMap<IBlockState> getCorrectStates()
    {
        return this.correctStateCounts;
    }

    @Nullable
    public BlockMismatch getMismatchForPosition(BlockPos pos)
    {
        return this.blockMismatches.get(pos);
    }

    public List<BlockMismatch> getMismatchOverviewFor(MismatchType type)
    {
        List<BlockMismatch> list = new ArrayList<>();

        if (type == MismatchType.ALL)
        {
            return this.getMismatchOverviewCombined();
        }
        else
        {
            this.addCountFor(type, this.getMapForMismatchType(type), list);
        }

        return list;
    }

    public List<BlockMismatch> getMismatchOverviewCombined()
    {
        List<BlockMismatch> list = new ArrayList<>();

        this.addCountFor(MismatchType.MISSING, this.missingBlocksPositions, list);
        this.addCountFor(MismatchType.EXTRA, this.extraBlocksPositions, list);
        this.addCountFor(MismatchType.WRONG_BLOCK, this.wrongBlocksPositions, list);
        this.addCountFor(MismatchType.WRONG_STATE, this.wrongStatesPositions, list);

        Collections.sort(list);

        return list;
    }

    private void addCountFor(MismatchType mismatchType, ArrayListMultimap<Pair<IBlockState, IBlockState>, BlockPos> map, List<BlockMismatch> list)
    {
        for (Pair<IBlockState, IBlockState> pair : map.keySet())
        {
            list.add(new BlockMismatch(mismatchType, pair.getLeft(), pair.getRight(), map.get(pair).size()));
        }
    }

    public List<Pair<IBlockState, IBlockState>> getIgnoredStateMismatchPairs()
    {
        List<Pair<IBlockState, IBlockState>> list = Lists.newArrayList(this.ignoredMismatches);
        list.sort(this::compareByRegistryName);
        return list;
    }

    protected int compareByRegistryName(Pair<IBlockState, IBlockState> p1, Pair<IBlockState, IBlockState> p2)
    {
        try
        {
            String name1 = Block.REGISTRY.getNameForObject(p1.getLeft().getBlock()).toString();
            String name2 = Block.REGISTRY.getNameForObject(p2.getLeft().getBlock()).toString();

            int val = name1.compareTo(name2);

            if (val < 0)
            {
                return -1;
            }
            else if (val > 0)
            {
                return 1;
            }
            else
            {
                name1 = Block.REGISTRY.getNameForObject(p1.getRight().getBlock()).toString();
                name2 = Block.REGISTRY.getNameForObject(p2.getRight().getBlock()).toString();

                return name1.compareTo(name2);
            }
        }
        catch (Exception e)
        {
            MessageDispatcher.error().translate("litematica.error.generic.failed_to_sort_list_of_ignored_states");
        }

        return 0;
    }

    private boolean verifyChunk(Chunk chunkClient, Chunk chunkSchematic, IntBoundingBox box)
    {
        LayerRange range = DataManager.getRenderLayerRange();
        EnumFacing.Axis axis = range.getAxis();
        boolean ranged = this.schematicPlacement.getSchematicVerifierType() == BlockInfoListType.RENDER_LAYERS;

        final int startX = ranged && axis == EnumFacing.Axis.X ? Math.max(box.minX, range.getMinLayerBoundary()) : box.minX;
        final int startY = ranged && axis == EnumFacing.Axis.Y ? Math.max(box.minY, range.getMinLayerBoundary()) : box.minY;
        final int startZ = ranged && axis == EnumFacing.Axis.Z ? Math.max(box.minZ, range.getMinLayerBoundary()) : box.minZ;
        final int endX = ranged && axis == EnumFacing.Axis.X ? Math.min(box.maxX, range.getMaxLayerBoundary()) : box.maxX;
        final int endY = ranged && axis == EnumFacing.Axis.Y ? Math.min(box.maxY, range.getMaxLayerBoundary()) : box.maxY;
        final int endZ = ranged && axis == EnumFacing.Axis.Z ? Math.min(box.maxZ, range.getMaxLayerBoundary()) : box.maxZ;

        for (int y = startY; y <= endY; ++y)
        {
            for (int z = startZ; z <= endZ; ++z)
            {
                for (int x = startX; x <= endX; ++x)
                {
                    MUTABLE_POS.setPos(x, y, z);
                    IBlockState stateClient = chunkClient.getBlockState(x, y, z).getActualState(chunkClient.getWorld(), MUTABLE_POS);
                    IBlockState stateSchematic = chunkSchematic.getBlockState(x, y, z);

                    this.checkBlockStates(x, y, z, stateSchematic, stateClient);

                    if (stateSchematic != AIR)
                    {
                        this.schematicBlocks++;
                    }

                    if (stateClient != AIR)
                    {
                        this.clientBlocks++;
                    }
                }
            }
        }

        return true;
    }

    private void checkBlockStates(int x, int y, int z, IBlockState stateSchematic, IBlockState stateClient)
    {
        BlockPos pos = new BlockPos(x, y, z);

        if (stateClient != stateSchematic)
        {
            MUTABLE_PAIR.setLeft(stateSchematic);
            MUTABLE_PAIR.setRight(stateClient);

            if (this.ignoredMismatches.contains(MUTABLE_PAIR) == false)
            {
                BlockMismatch mismatch = null;

                if (stateSchematic != AIR)
                {
                    if (stateClient == AIR)
                    {
                        mismatch = new BlockMismatch(MismatchType.MISSING, stateSchematic, stateClient, 1);
                        this.missingBlocksPositions.put(Pair.of(stateSchematic, stateClient), pos);
                    }
                    else
                    {
                        if (stateSchematic.getBlock() != stateClient.getBlock())
                        {
                            mismatch = new BlockMismatch(MismatchType.WRONG_BLOCK, stateSchematic, stateClient, 1);
                            this.wrongBlocksPositions.put(Pair.of(stateSchematic, stateClient), pos);
                        }
                        else
                        {
                            mismatch = new BlockMismatch(MismatchType.WRONG_STATE, stateSchematic, stateClient, 1);
                            this.wrongStatesPositions.put(Pair.of(stateSchematic, stateClient), pos);
                        }
                    }
                }
                else if (Configs.Visuals.IGNORE_EXISTING_FLUIDS.getBooleanValue() == false || stateClient.getMaterial().isLiquid() == false)
                {
                    mismatch = new BlockMismatch(MismatchType.EXTRA, stateSchematic, stateClient, 1);
                    this.extraBlocksPositions.put(Pair.of(stateSchematic, stateClient), pos);
                }

                if (mismatch != null)
                {
                    this.blockMismatches.put(pos, mismatch);

                    ItemUtils.setItemForBlock(this.worldClient, pos, stateClient);
                    ItemUtils.setItemForBlock(this.worldSchematic, pos, stateSchematic);
                }
            }
        }
        else
        {
            ItemUtils.setItemForBlock(this.worldClient, pos, stateClient);
            this.correctStateCounts.addTo(stateClient, 1);

            if (stateSchematic != AIR)
            {
                ++this.correctStatesCount;
            }
        }
    }

    private void updateMismatchOverlays()
    {
        Minecraft mc = Minecraft.getMinecraft();

        if (mc.player != null)
        {
            int maxEntries = Configs.InfoOverlays.VERIFIER_ERROR_HILIGHT_MAX_POSITIONS.getIntegerValue();

            // This needs to happen first
            BlockPos centerPos = new BlockPos(mc.player.getPositionVector());
            this.updateClosestPositions(centerPos);
            this.combineClosestPositions(centerPos, maxEntries);

            // Only one category selected, show the title
            if (this.selectedCategories.size() == 1 && this.selectedEntries.size() == 0)
            {
                MismatchType type = this.mismatchPositionsForRender.size() > 0 ? this.mismatchPositionsForRender.get(0).type : null;
                this.updateMismatchPositionStringList(type, this.mismatchPositionsForRender);
            }
            else
            {
                this.updateMismatchPositionStringList(null, this.mismatchPositionsForRender);
            }
        }
    }

    private void updateClosestPositions(BlockPos centerPos)
    {
        PositionUtils.BLOCK_POS_COMPARATOR.setReferencePosition(centerPos);
        PositionUtils.BLOCK_POS_COMPARATOR.setClosestFirst(true);

        this.addAndSortPositions(MismatchType.WRONG_BLOCK,  this.wrongBlocksPositions, this.mismatchedBlocksPositionsClosest);
        this.addAndSortPositions(MismatchType.WRONG_STATE,  this.wrongStatesPositions, this.mismatchedStatesPositionsClosest);
        this.addAndSortPositions(MismatchType.EXTRA,        this.extraBlocksPositions, this.extraBlocksPositionsClosest);
        this.addAndSortPositions(MismatchType.MISSING,      this.missingBlocksPositions, this.missingBlocksPositionsClosest);
    }

    private void addAndSortPositions(MismatchType type,
                                     ArrayListMultimap<Pair<IBlockState, IBlockState>, BlockPos> sourceMap,
                                     List<BlockPos> listOut)
    {
        listOut.clear();

        //List<BlockPos> tempList = new ArrayList<>();

        if (this.selectedCategories.contains(type))
        {
            listOut.addAll(sourceMap.values());
        }
        else
        {
            Collection<BlockMismatch> mismatches = this.selectedEntries.get(type);

            for (BlockMismatch mismatch : mismatches)
            {
                MUTABLE_PAIR.setLeft(mismatch.stateExpected);
                MUTABLE_PAIR.setRight(mismatch.stateFound);
                listOut.addAll(sourceMap.get(MUTABLE_PAIR));
            }
        }

        listOut.sort(PositionUtils.BLOCK_POS_COMPARATOR);

        /*
        final int max = Math.min(maxEntries, tempList.size());

        for (int i = 0; i < max; ++i)
        {
            listOut.add(tempList.get(i));
        }
        */
    }

    private void combineClosestPositions(BlockPos centerPos, int maxEntries)
    {
        this.mismatchPositionsForRender.clear();
        this.mismatchBlockPositionsForRender.clear();

        List<MismatchRenderPos> tempList = new ArrayList<>();

        this.getMismatchRenderPositionFor(MismatchType.WRONG_BLOCK, tempList);
        this.getMismatchRenderPositionFor(MismatchType.WRONG_STATE, tempList);
        this.getMismatchRenderPositionFor(MismatchType.EXTRA, tempList);
        this.getMismatchRenderPositionFor(MismatchType.MISSING, tempList);

        tempList.sort(new RenderPosComparator(centerPos, true));

        final int max = Math.min(maxEntries, tempList.size());

        for (int i = 0; i < max; ++i)
        {
            MismatchRenderPos entry = tempList.get(i);
            this.mismatchPositionsForRender.add(entry);
            this.mismatchBlockPositionsForRender.add(entry.pos);
        }
    }

    private void getMismatchRenderPositionFor(MismatchType type, List<MismatchRenderPos> listOut)
    {
        List<BlockPos> list = this.getClosestMismatchedPositionsFor(type);

        for (BlockPos pos : list)
        {
            listOut.add(new MismatchRenderPos(type, pos));
        }
    }

    private List<BlockPos> getClosestMismatchedPositionsFor(MismatchType type)
    {
        switch (type)
        {
            case MISSING:
                return this.missingBlocksPositionsClosest;
            case EXTRA:
                return this.extraBlocksPositionsClosest;
            case WRONG_BLOCK:
                return this.mismatchedBlocksPositionsClosest;
            case WRONG_STATE:
                return this.mismatchedStatesPositionsClosest;
            default:
                return Collections.emptyList();
        }
    }

    private void updateMismatchPositionStringList(@Nullable MismatchType mismatchType, List<MismatchRenderPos> positionList)
    {
        List<String> hudLines = new ArrayList<>();

        if (positionList.isEmpty() == false)
        {
            if (mismatchType != null)
            {
                hudLines.add(mismatchType.getTitleDisplayName());
            }
            else
            {
                hudLines.add(StringUtils.translate("litematica.title.hud.schematic_verifier.errors"));
            }

            final int count = Math.min(positionList.size(), Configs.InfoOverlays.INFO_HUD_MAX_LINES.getIntegerValue());

            for (int i = 0; i < count; ++i)
            {
                MismatchRenderPos entry = positionList.get(i);
                hudLines.add(entry.type.getHudPositionLine(entry.pos));
            }
        }

        this.infoHudLines = hudLines;
    }

    public void updateRequiredChunksStringList()
    {
        this.updateInfoHudLinesMissingChunks(this.requiredChunks);
    }

    /**
     * Prepares/caches the strings, and returns a provider for the data.<br>
     * <b>NOTE:</b> This is actually the instance of this class, there are no separate providers for different data types atm!
     */
    /*
    public IInfoHudRenderer getClosestMismatchedPositionListProviderFor(MismatchType type)
    {
        return this;
    }
    */

    public static class BlockMismatch implements Comparable<BlockMismatch>
    {
        public final MismatchType mismatchType;
        public final IBlockState stateExpected;
        public final IBlockState stateFound;
        public final int count;

        public BlockMismatch(MismatchType mismatchType, IBlockState stateExpected, IBlockState stateFound, int count)
        {
            this.mismatchType = mismatchType;
            this.stateExpected = stateExpected;
            this.stateFound = stateFound;
            this.count = count;
        }

        @Override
        public int compareTo(BlockMismatch other)
        {
            return Integer.compare(other.count, this.count);
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((this.mismatchType == null) ? 0 : this.mismatchType.hashCode());
            result = prime * result + ((this.stateExpected == null) ? 0 : this.stateExpected.hashCode());
            result = prime * result + ((this.stateFound == null) ? 0 : this.stateFound.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (this.getClass() != obj.getClass())
                return false;
            BlockMismatch other = (BlockMismatch) obj;
            if (this.mismatchType != other.mismatchType)
                return false;
            if (this.stateExpected == null)
            {
                if (other.stateExpected != null)
                    return false;
            }
            else if (this.stateExpected != other.stateExpected)
                return false;
            if (this.stateFound == null)
            {
                return other.stateFound == null;
            }
            else return this.stateFound == other.stateFound;
        }
    }

    public static class MismatchRenderPos
    {
        public final MismatchType type;
        public final BlockPos pos;

        public MismatchRenderPos(MismatchType type, BlockPos pos)
        {
            this.type = type;
            this.pos = pos;
        }
    }

    private static class RenderPosComparator implements Comparator<MismatchRenderPos>
    {
        private final BlockPos posReference;
        private final boolean closestFirst;

        public RenderPosComparator(BlockPos posReference, boolean closestFirst)
        {
            this.posReference = posReference;
            this.closestFirst = closestFirst;
        }

        @Override
        public int compare(MismatchRenderPos pos1, MismatchRenderPos pos2)
        {
            double dist1 = pos1.pos.distanceSq(this.posReference);
            double dist2 = pos2.pos.distanceSq(this.posReference);

            if (dist1 == dist2)
            {
                return 0;
            }

            return dist1 < dist2 == this.closestFirst ? -1 : 1;
        }
    }

    public enum MismatchType
    {
        ALL             ("litematica.name.schematic_verifier.all",           Configs.Colors.VERIFIER_CORRECT), // color not used
        CORRECT_STATE   ("litematica.name.schematic_verifier.correct_state", Configs.Colors.VERIFIER_CORRECT),
        EXTRA           ("litematica.name.schematic_verifier.extra",         Configs.Colors.VERIFIER_EXTRA),
        MISSING         ("litematica.name.schematic_verifier.missing",       Configs.Colors.VERIFIER_MISSING),
        WRONG_BLOCK     ("litematica.name.schematic_verifier.wrong_blocks",  Configs.Colors.VERIFIER_WRONG_BLOCK),
        WRONG_STATE     ("litematica.name.schematic_verifier.wrong_state",   Configs.Colors.VERIFIER_WRONG_STATE);

        private final String translationKey;
        private final ColorConfig colorConfig;

        MismatchType(String translationKey, ColorConfig colorConfig)
        {
            this.translationKey = translationKey;
            this.colorConfig = colorConfig;
        }

        public Color4f getColor()
        {
            return this.colorConfig.getColor();
        }

        public String getDisplayName()
        {
            return StringUtils.translate(this.translationKey);
        }

        public String getTitleDisplayName()
        {
            return StringUtils.translate(this.translationKey + ".title");
        }

        public String getHudPositionLine(BlockPos pos)
        {
            return StringUtils.translate(this.translationKey + ".pos_line", pos.getX(), pos.getY(), pos.getZ());
        }
    }

    public enum SortCriteria
    {
        NAME_EXPECTED,
        NAME_FOUND,
        COUNT;
    }
}
